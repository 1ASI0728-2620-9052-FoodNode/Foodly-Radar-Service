package pe.foodly.radar.application.events;

import java.time.Instant;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;

/** Evento de integración de Business: se registró un nuevo establecimiento (5.3.4). */
public record HuariqueRegisteredEvent(String eventId, String huariqueId, long version, Instant occurredAt,
                                      double latitude, double longitude, AvailabilityStatus status) {
}
