package pe.foodly.radar.infrastructure.messaging;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import pe.foodly.radar.application.eventhandlers.HuariqueRegisteredEventHandler;
import pe.foodly.radar.application.eventhandlers.HuariqueUpdatedEventHandler;
import pe.foodly.radar.application.eventhandlers.LocationUpdatedEventHandler;
import pe.foodly.radar.application.eventhandlers.OpeningStatusChangedEventHandler;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.infrastructure.persistence.redis.HuariqueVersionStore;

/**
 * Escucha la cola durable de Radar y deriva cada evento de Business a su handler (5.2.4).
 *
 * <ol>
 *   <li>Deserializa el sobre. Si no se puede leer, va a la DLQ sin reintentos.</li>
 *   <li>Un tipo que Radar no procesa (por ejemplo {@code MenuUpdated}) se confirma y se registra en debug.</li>
 *   <li>Si la {@code version} del evento es menor o igual a la del hash, se descarta y se confirma.</li>
 *   <li>Si no, delega en el handler del tipo, guarda la nueva versión y recién entonces confirma (ack manual).</li>
 *   <li>Si el procesamiento falla se reintenta en memoria, con espera creciente. Al agotar los intentos,
 *       {@code basicNack(requeue=false)} y el mensaje pasa a la Dead Letter Queue.</li>
 * </ol>
 *
 * <p>Un evento mal formado o con datos inválidos no se reintenta: reintentar no lo arregla.
 * Los eventos se procesan de uno en uno (prefetch 1), en el orden en que llegan.
 */
@Component
public class BusinessEventListener {

    private static final Logger log = LoggerFactory.getLogger(BusinessEventListener.class);

    private final BusinessEventParser parser;
    private final HuariqueRegisteredEventHandler registeredHandler;
    private final HuariqueUpdatedEventHandler updatedHandler;
    private final LocationUpdatedEventHandler locationUpdatedHandler;
    private final OpeningStatusChangedEventHandler openingStatusChangedHandler;
    private final HuariqueVersionStore versionStore;
    private final EventRetryProperties retry;
    private final Sleeper sleeper;

    public BusinessEventListener(BusinessEventParser parser,
                                 HuariqueRegisteredEventHandler registeredHandler,
                                 HuariqueUpdatedEventHandler updatedHandler,
                                 LocationUpdatedEventHandler locationUpdatedHandler,
                                 OpeningStatusChangedEventHandler openingStatusChangedHandler,
                                 HuariqueVersionStore versionStore,
                                 EventRetryProperties retry,
                                 Sleeper sleeper) {
        this.parser = parser;
        this.registeredHandler = registeredHandler;
        this.updatedHandler = updatedHandler;
        this.locationUpdatedHandler = locationUpdatedHandler;
        this.openingStatusChangedHandler = openingStatusChangedHandler;
        this.versionStore = versionStore;
        this.retry = retry;
        this.sleeper = sleeper;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        BusinessEventEnvelope envelope;
        try {
            envelope = parser.parse(message.getBody());
        } catch (MalformedBusinessEventException e) {
            log.error("[eventId=? huariqueId=?] Mensaje ilegible, va a la DLQ: {}", e.getMessage());
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String context = "[eventId=" + envelope.eventId() + " huariqueId=" + envelope.huariqueId() + "]";
        log.info("{} Recibido {} (version {}).", context, envelope.eventType(), envelope.version());

        Optional<BusinessEventType> type = BusinessEventType.fromEventType(envelope.eventType());
        if (type.isEmpty()) {
            log.debug("{} Tipo de evento '{}' no lo procesa Radar: se confirma y se ignora.",
                    context, envelope.eventType());
            channel.basicAck(deliveryTag, false);
            return;
        }

        Outcome outcome;
        try {
            outcome = processWithRetry(envelope, type.get(), context);
        } catch (ProcessingInterrupted e) {
            // Apagado en curso: sin ack ni nack, RabbitMQ reentrega el mensaje cuando Radar vuelva.
            log.warn("{} Procesamiento interrumpido; el mensaje se reentregará.", context);
            return;
        } catch (RuntimeException e) {
            log.error("{} No se pudo procesar {}; va a la DLQ.", context, envelope.eventType(), e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        channel.basicAck(deliveryTag, false);
        log.info("{} Confirmado (ack): {}.", context, outcome);
    }

    private Outcome processWithRetry(BusinessEventEnvelope envelope, BusinessEventType type, String context) {
        long backoffMs = retry.initialBackoffMs();
        for (int attempt = 1; ; attempt++) {
            try {
                return apply(envelope, type, context);
            } catch (MalformedBusinessEventException | IllegalArgumentException e) {
                // Datos inválidos: reintentar no cambia nada.
                log.error("{} Datos inválidos, no se reintenta: {}", context, e.getMessage());
                throw e;
            } catch (RuntimeException e) {
                if (attempt >= retry.maxAttempts()) {
                    log.error("{} Intento {} de {} fallido; se agotaron los intentos.",
                            context, attempt, retry.maxAttempts());
                    throw e;
                }
                log.warn("{} Intento {} de {} fallido ({}); se reintenta en {} ms.",
                        context, attempt, retry.maxAttempts(), e.getMessage(), backoffMs);
                pause(backoffMs);
                backoffMs = Math.round(backoffMs * retry.backoffMultiplier());
            }
        }
    }

    private Outcome apply(BusinessEventEnvelope envelope, BusinessEventType type, String context) {
        long eventVersion = parser.requireIdentity(envelope);
        HuariqueId huariqueId = new HuariqueId(envelope.huariqueId());

        Optional<Long> storedVersion = versionStore.findVersion(huariqueId);
        if (EventVersionRule.isStale(storedVersion, eventVersion)) {
            log.info("{} Descartado: la versión {} no supera a la almacenada ({}).",
                    context, eventVersion, storedVersion.orElseThrow());
            return Outcome.DISCARDED_STALE;
        }

        log.info("{} Versión {} vigente (almacenada: {}); se delega en el handler de {}.",
                context, eventVersion, storedVersion.map(String::valueOf).orElse("ninguna"), type.eventType());
        switch (type) {
            case HUARIQUE_REGISTERED -> registeredHandler.handle(parser.toRegistered(envelope));
            case HUARIQUE_UPDATED -> updatedHandler.handle(parser.toUpdated(envelope));
            case LOCATION_UPDATED -> locationUpdatedHandler.handle(parser.toLocationUpdated(envelope));
            case OPENING_STATUS_CHANGED ->
                    openingStatusChangedHandler.handle(parser.toOpeningStatusChanged(envelope));
        }

        versionStore.writeVersion(huariqueId, eventVersion);
        log.info("{} Redis actualizado; versión {} guardada.", context, eventVersion);
        return Outcome.APPLIED;
    }

    private void pause(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingInterrupted();
        }
    }

    private enum Outcome {
        APPLIED,
        DISCARDED_STALE
    }

    private static final class ProcessingInterrupted extends RuntimeException {
        ProcessingInterrupted() {
            super(null, null, false, false);
        }
    }
}
