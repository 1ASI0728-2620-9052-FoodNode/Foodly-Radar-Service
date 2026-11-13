package pe.foodly.radar.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Sobre de los eventos de Business: { eventId, eventType, huariqueId, version, occurredAt, payload }.
 * Los campos se validan en {@link BusinessEventParser} según el tipo de evento.
 */
public record BusinessEventEnvelope(
        String eventId,
        String eventType,
        String huariqueId,
        Long version,
        Instant occurredAt,
        Payload payload) {

    /** { latitude, longitude, status o isOpen }. */
    public record Payload(
            Double latitude,
            Double longitude,
            String status,
            @JsonProperty("isOpen") Boolean isOpen) {
    }
}
