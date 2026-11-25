package pe.foodly.radar.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import pe.foodly.radar.application.events.HuariqueRegisteredEvent;
import pe.foodly.radar.application.events.HuariqueUpdatedEvent;
import pe.foodly.radar.application.events.OpeningStatusChangedEvent;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;

/**
 * Deserializa el sobre de Business v1 y lo traduce a los eventos de la capa de aplicación.
 * Los mensajes de error no incluyen el contenido del mensaje.
 */
@Component
public class BusinessEventParser {

    /** Única versión del contrato de Business que Radar entiende. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public BusinessEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Lee el sobre. Solo exige que sea un JSON con {@code type}; el resto se valida según el tipo. */
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
        if (envelope == null || isBlank(envelope.type())) {
            throw new MalformedBusinessEventException("el sobre no trae type");
        }
        return envelope;
    }

    /**
     * Valida lo que hace falta para comparar versiones: schemaVersion, huariqueId y aggregateVersion.
     * La versión del agregado empieza en 0, así que 0 es válida.
     */
    public long requireIdentity(BusinessEventEnvelope envelope) {
        if (envelope.schemaVersion() == null || envelope.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new MalformedBusinessEventException("el sobre no trae una schemaVersion soportada");
        }
        if (isBlank(envelope.huariqueId())) {
            throw new MalformedBusinessEventException("el sobre no trae huariqueId");
        }
        if (envelope.aggregateVersion() == null || envelope.aggregateVersion() < 0) {
            throw new MalformedBusinessEventException("el sobre no trae una aggregateVersion válida");
        }
        return envelope.aggregateVersion();
    }

    public HuariqueRegisteredEvent toRegistered(BusinessEventEnvelope envelope) {
        BusinessEventEnvelope.Data data = dataOf(envelope);
        BusinessEventEnvelope.Location location = locationOf(data);
        return new HuariqueRegisteredEvent(envelope.eventId(), envelope.huariqueId(), requireIdentity(envelope),
                envelope.occurredAt(), requireNumber(location.latitude(), "location.latitude"),
                requireNumber(location.longitude(), "location.longitude"), statusOf(data));
    }

    public HuariqueUpdatedEvent toUpdated(BusinessEventEnvelope envelope) {
        BusinessEventEnvelope.Data data = dataOf(envelope);
        BusinessEventEnvelope.Location location = locationOf(data);
        return new HuariqueUpdatedEvent(envelope.eventId(), envelope.huariqueId(), requireIdentity(envelope),
                envelope.occurredAt(), requireNumber(location.latitude(), "location.latitude"),
                requireNumber(location.longitude(), "location.longitude"), statusOf(data));
    }

    public OpeningStatusChangedEvent toOpeningStatusChanged(BusinessEventEnvelope envelope) {
        BusinessEventEnvelope.Data data = dataOf(envelope);
        return new OpeningStatusChangedEvent(envelope.eventId(), envelope.huariqueId(), requireIdentity(envelope),
                envelope.occurredAt(), statusOf(data));
    }

    private static BusinessEventEnvelope.Data dataOf(BusinessEventEnvelope envelope) {
        if (envelope.data() == null) {
            throw new MalformedBusinessEventException("el sobre no trae data");
        }
        return envelope.data();
    }

    private static BusinessEventEnvelope.Location locationOf(BusinessEventEnvelope.Data data) {
        if (data.location() == null) {
            throw new MalformedBusinessEventException("data no trae location");
        }
        return data.location();
    }

    private static double requireNumber(Double value, String field) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            throw new MalformedBusinessEventException("data no trae " + field + " válido");
        }
        return value;
    }

    /** {@code data.openStatus} de Business: {@code OPEN} es ACTIVE y {@code CLOSED} es INACTIVE. */
    private static AvailabilityStatus statusOf(BusinessEventEnvelope.Data data) {
        if (isBlank(data.openStatus())) {
            throw new MalformedBusinessEventException("data no trae openStatus");
        }
        return switch (data.openStatus().trim().toUpperCase(Locale.ROOT)) {
            case "OPEN" -> AvailabilityStatus.ACTIVE;
            case "CLOSED" -> AvailabilityStatus.INACTIVE;
            default -> throw new MalformedBusinessEventException("data trae un openStatus desconocido");
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
