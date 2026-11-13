package pe.foodly.radar.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import pe.foodly.radar.application.events.HuariqueRegisteredEvent;
import pe.foodly.radar.application.events.HuariqueUpdatedEvent;
import pe.foodly.radar.application.events.LocationUpdatedEvent;
import pe.foodly.radar.application.events.OpeningStatusChangedEvent;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;

/**
 * Deserializa el sobre de Business y lo traduce a los eventos de la capa de aplicación.
 * Los mensajes de error no incluyen el contenido del mensaje.
 */
@Component
public class BusinessEventParser {

    private final ObjectMapper objectMapper;

    public BusinessEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Lee el sobre. Solo exige que sea un JSON con {@code eventType}; el resto se valida según el tipo. */
    public BusinessEventEnvelope parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new MalformedBusinessEventException("el mensaje está vacío");
        }
        BusinessEventEnvelope envelope;
        try {
            envelope = objectMapper.readValue(body, BusinessEventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new MalformedBusinessEventException("el mensaje no es un sobre de evento válido", e);
        } catch (IOException e) {
            throw new MalformedBusinessEventException("no se pudo leer el mensaje", e);
        }
        if (envelope == null || isBlank(envelope.eventType())) {
            throw new MalformedBusinessEventException("el sobre no trae eventType");
        }
        return envelope;
    }

    /** Valida lo que hace falta para comparar versiones: huariqueId y version. */
    public long requireIdentity(BusinessEventEnvelope envelope) {
        if (isBlank(envelope.huariqueId())) {
            throw new MalformedBusinessEventException("el sobre no trae huariqueId");
        }
        if (envelope.version() == null || envelope.version() < 0) {
            throw new MalformedBusinessEventException("el sobre no trae una version válida");
        }
        return envelope.version();
    }

    public HuariqueRegisteredEvent toRegistered(BusinessEventEnvelope envelope) {
        BusinessEventEnvelope.Payload payload = payloadOf(envelope);
        return new HuariqueRegisteredEvent(envelope.eventId(), envelope.huariqueId(), requireIdentity(envelope),
                envelope.occurredAt(), requireNumber(payload.latitude(), "latitude"),
                requireNumber(payload.longitude(), "longitude"), statusOf(payload));
    }

    public HuariqueUpdatedEvent toUpdated(BusinessEventEnvelope envelope) {
        BusinessEventEnvelope.Payload payload = payloadOf(envelope);
        return new HuariqueUpdatedEvent(envelope.eventId(), envelope.huariqueId(), requireIdentity(envelope),
                envelope.occurredAt(), requireNumber(payload.latitude(), "latitude"),
                requireNumber(payload.longitude(), "longitude"), statusOf(payload));
    }

    public LocationUpdatedEvent toLocationUpdated(BusinessEventEnvelope envelope) {
        BusinessEventEnvelope.Payload payload = payloadOf(envelope);
        return new LocationUpdatedEvent(envelope.eventId(), envelope.huariqueId(), requireIdentity(envelope),
                envelope.occurredAt(), requireNumber(payload.latitude(), "latitude"),
                requireNumber(payload.longitude(), "longitude"), statusOf(payload));
    }

    public OpeningStatusChangedEvent toOpeningStatusChanged(BusinessEventEnvelope envelope) {
        BusinessEventEnvelope.Payload payload = payloadOf(envelope);
        return new OpeningStatusChangedEvent(envelope.eventId(), envelope.huariqueId(), requireIdentity(envelope),
                envelope.occurredAt(), statusOf(payload));
    }

    private static BusinessEventEnvelope.Payload payloadOf(BusinessEventEnvelope envelope) {
        if (envelope.payload() == null) {
            throw new MalformedBusinessEventException("el sobre no trae payload");
        }
        return envelope.payload();
    }

    private static double requireNumber(Double value, String field) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            throw new MalformedBusinessEventException("el payload no trae " + field + " válido");
        }
        return value;
    }

    /** {@code status} (ACTIVE o INACTIVE) o, si no viene, {@code isOpen} (true es ACTIVE). */
    private static AvailabilityStatus statusOf(BusinessEventEnvelope.Payload payload) {
        if (!isBlank(payload.status())) {
            try {
                return AvailabilityStatus.valueOf(payload.status().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new MalformedBusinessEventException("el payload trae un status desconocido");
            }
        }
        if (payload.isOpen() != null) {
            return payload.isOpen() ? AvailabilityStatus.ACTIVE : AvailabilityStatus.INACTIVE;
        }
        throw new MalformedBusinessEventException("el payload no trae status ni isOpen");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
