package pe.foodly.radar.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import pe.foodly.radar.application.eventhandlers.HuariqueRegisteredEventHandler;
import pe.foodly.radar.application.eventhandlers.HuariqueUpdatedEventHandler;
import pe.foodly.radar.application.eventhandlers.LocationUpdatedEventHandler;
import pe.foodly.radar.application.eventhandlers.OpeningStatusChangedEventHandler;
import pe.foodly.radar.application.events.HuariqueRegisteredEvent;
import pe.foodly.radar.application.events.HuariqueUpdatedEvent;
import pe.foodly.radar.application.events.LocationUpdatedEvent;
import pe.foodly.radar.application.events.OpeningStatusChangedEvent;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.infrastructure.persistence.redis.HuariqueVersionStore;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessEventListenerTest {

    private static final long TAG = 7L;
    private static final HuariqueId ID = new HuariqueId("h-1");

    @Mock
    private HuariqueRegisteredEventHandler registeredHandler;
    @Mock
    private HuariqueUpdatedEventHandler updatedHandler;
    @Mock
    private LocationUpdatedEventHandler locationUpdatedHandler;
    @Mock
    private OpeningStatusChangedEventHandler openingStatusChangedHandler;
    @Mock
    private HuariqueVersionStore versionStore;
    @Mock
    private Channel channel;

    private final List<Long> sleeps = new ArrayList<>();
    private BusinessEventListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        listener = new BusinessEventListener(new BusinessEventParser(mapper), registeredHandler, updatedHandler,
                locationUpdatedHandler, openingStatusChangedHandler, versionStore,
                new EventRetryProperties(3, 100, 2.0), sleeps::add);
        when(versionStore.findVersion(ID)).thenReturn(Optional.empty());
    }

    private static Message message(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(TAG);
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }

    private static String registered(long version) {
        return event("HuariqueRegistered", version,
                "{ \"latitude\": -12.1219, \"longitude\": -77.0297, \"status\": \"ACTIVE\" }");
    }

    private static String event(String type, long version, String payload) {
        return """
                { "eventId": "e-1", "eventType": "%s", "huariqueId": "h-1", "version": %d,
                  "occurredAt": "2026-11-13T12:00:00Z", "payload": %s }""".formatted(type, version, payload);
    }

    private void verifyAckedOnly() throws Exception {
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    private void verifyNackedToDlqOnly() throws Exception {
        verify(channel).basicNack(TAG, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    // ---- caso feliz y orden del ack ----

    @Test
    void appliesANewerEventStoresTheVersionAndAcksOnlyAfterRedisIsUpdated() throws Exception {
        when(versionStore.findVersion(ID)).thenReturn(Optional.of(3L));

        listener.onMessage(message(registered(4)), channel);

        InOrder order = inOrder(registeredHandler, versionStore, channel);
        order.verify(registeredHandler).handle(new HuariqueRegisteredEvent("e-1", "h-1", 4,
                Instant.parse("2026-11-13T12:00:00Z"), -12.1219, -77.0297, AvailabilityStatus.ACTIVE));
        order.verify(versionStore).writeVersion(ID, 4);
        order.verify(channel).basicAck(TAG, false);
        verifyAckedOnly();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void appliesTheFirstEventOfAHuariqueThatHasNoStoredVersion() throws Exception {
        listener.onMessage(message(registered(0)), channel);

        verify(registeredHandler).handle(any());
        verify(versionStore).writeVersion(ID, 0);
        verifyAckedOnly();
    }

    @Test
    void dispatchesEachTypeToItsOwnHandler() throws Exception {
        String coordinates = "{ \"latitude\": -12.15, \"longitude\": -77.02, \"status\": \"ACTIVE\" }";

        listener.onMessage(message(event("HuariqueUpdated", 1, coordinates)), channel);
        verify(updatedHandler).handle(any(HuariqueUpdatedEvent.class));

        listener.onMessage(message(event("LocationUpdated", 2, coordinates)), channel);
        verify(locationUpdatedHandler).handle(any(LocationUpdatedEvent.class));

        listener.onMessage(message(event("OpeningStatusChanged", 3, "{ \"isOpen\": false }")), channel);
        verify(openingStatusChangedHandler).handle(
                new OpeningStatusChangedEvent("e-1", "h-1", 3, Instant.parse("2026-11-13T12:00:00Z"),
                        AvailabilityStatus.INACTIVE));

        verifyNoInteractions(registeredHandler);
    }

    // ---- regla de versión ----

    @Test
    void anEventWithTheSameVersionIsAckedAndDiscarded() throws Exception {
        when(versionStore.findVersion(ID)).thenReturn(Optional.of(4L));

        listener.onMessage(message(registered(4)), channel);

        verifyNoInteractions(registeredHandler);
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyAckedOnly();
    }

    @Test
    void anOlderEventIsAckedAndDiscarded() throws Exception {
        when(versionStore.findVersion(ID)).thenReturn(Optional.of(9L));

        listener.onMessage(message(registered(2)), channel);

        verifyNoInteractions(registeredHandler);
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyAckedOnly();
    }

    // ---- tipos que Radar no procesa ----

    @Test
    void menuUpdatedIsAckedAndIgnoredWithoutTouchingRedis() throws Exception {
        listener.onMessage(message(event("MenuUpdated", 5, "{ \"platos\": [] }")), channel);

        verifyNoInteractions(registeredHandler, updatedHandler, locationUpdatedHandler,
                openingStatusChangedHandler, versionStore);
        verifyAckedOnly();
    }

    @Test
    void anUnknownTypeIsAckedAndIgnored() throws Exception {
        listener.onMessage(message(event("TipoNuevo", 1, "{}")), channel);

        verifyNoInteractions(registeredHandler, updatedHandler, locationUpdatedHandler,
                openingStatusChangedHandler, versionStore);
        verifyAckedOnly();
    }

    // ---- datos inválidos: directo a la DLQ, sin reintentos ----

    @Test
    void anUnreadableMessageGoesStraightToTheDlq() throws Exception {
        listener.onMessage(message("{{{ no es json"), channel);

        verifyNoInteractions(registeredHandler, versionStore);
        verifyNackedToDlqOnly();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aPayloadMissingRequiredFieldsGoesStraightToTheDlq() throws Exception {
        listener.onMessage(message(event("HuariqueRegistered", 1, "{ \"status\": \"ACTIVE\" }")), channel);

        verifyNoInteractions(registeredHandler);
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyNackedToDlqOnly();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void invalidDomainDataFromTheHandlerIsNotRetried() throws Exception {
        doThrow(new IllegalArgumentException("La latitud debe estar entre -90 y 90."))
                .when(registeredHandler).handle(any());

        listener.onMessage(message(registered(1)), channel);

        verify(registeredHandler).handle(any());
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyNackedToDlqOnly();
        assertThat(sleeps).isEmpty();
    }

    // ---- reintento en memoria ----

    @Test
    void retriesWithGrowingBackoffAndAcksWhenALaterAttemptSucceeds() throws Exception {
        doThrow(new IllegalStateException("redis caído")).doThrow(new IllegalStateException("redis caído"))
                .doNothing().when(registeredHandler).handle(any());

        listener.onMessage(message(registered(1)), channel);

        verify(registeredHandler, org.mockito.Mockito.times(3)).handle(any());
        assertThat(sleeps).containsExactly(100L, 200L);
        verify(versionStore).writeVersion(ID, 1);
        verifyAckedOnly();
    }

    @Test
    void afterThreeFailedAttemptsTheMessageIsNackedWithoutRequeue() throws Exception {
        doThrow(new IllegalStateException("redis caído")).when(registeredHandler).handle(any());

        listener.onMessage(message(registered(1)), channel);

        verify(registeredHandler, org.mockito.Mockito.times(3)).handle(any());
        assertThat(sleeps).containsExactly(100L, 200L);
        verify(versionStore, never()).writeVersion(any(), anyLong());
        verifyNackedToDlqOnly();
    }

    @Test
    void aFailureWhileStoringTheVersionIsRetriedAndNothingIsAckedInBetween() throws Exception {
        doThrow(new IllegalStateException("redis caído")).doNothing().when(versionStore).writeVersion(ID, 1);

        listener.onMessage(message(registered(1)), channel);

        verify(registeredHandler, org.mockito.Mockito.times(2)).handle(any());
        verify(channel, org.mockito.Mockito.times(1)).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void anInterruptedWaitLeavesTheMessageUnackedSoItIsRedelivered() throws Exception {
        BusinessEventListener interrupted = new BusinessEventListener(
                new BusinessEventParser(new ObjectMapper().findAndRegisterModules()), registeredHandler,
                updatedHandler, locationUpdatedHandler, openingStatusChangedHandler, versionStore,
                new EventRetryProperties(3, 100, 2.0), millis -> {
                    throw new InterruptedException();
                });
        doThrow(new IllegalStateException("redis caído")).when(registeredHandler).handle(any());

        try {
            interrupted.onMessage(message(registered(1)), channel);

            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // no contaminar otras pruebas
        }
    }

    @Test
    void aSingleAttemptConfigurationNeverSleeps() throws Exception {
        BusinessEventListener oneAttempt = new BusinessEventListener(
                new BusinessEventParser(new ObjectMapper().findAndRegisterModules()), registeredHandler,
                updatedHandler, locationUpdatedHandler, openingStatusChangedHandler, versionStore,
                new EventRetryProperties(1, 100, 2.0), sleeps::add);
        doThrow(new IllegalStateException("redis caído")).when(registeredHandler).handle(any());

        oneAttempt.onMessage(message(registered(1)), channel);

        verify(registeredHandler).handle(any());
        assertThat(sleeps).isEmpty();
        verify(channel).basicNack(TAG, false, false);
    }
}
