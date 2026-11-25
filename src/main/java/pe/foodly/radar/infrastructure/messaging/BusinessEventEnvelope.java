package pe.foodly.radar.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Sobre de los eventos de Business v1: { eventId, type, schemaVersion, huariqueId, aggregateVersion, occurredAt,
 * data } (ver {@code Foodly-Business-Service/docs/contracts.md}). {@code data} es el snapshot completo del
 * huarique; Radar solo lee {@code data.location} y {@code data.openStatus}. Los campos desconocidos se ignoran.
 * Los campos se validan en {@link BusinessEventParser} según el tipo de evento.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessEventEnvelope(
        String eventId,
        String type,
        Integer schemaVersion,
        String huariqueId,
        Long aggregateVersion,
        Instant occurredAt,
        Data data) {

    /** Lo que Radar necesita del snapshot del huarique. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(Location location, String openStatus) {
    }

    /** {@code data.location}: { latitude, longitude }. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(Double latitude, Double longitude) {
    }
}
