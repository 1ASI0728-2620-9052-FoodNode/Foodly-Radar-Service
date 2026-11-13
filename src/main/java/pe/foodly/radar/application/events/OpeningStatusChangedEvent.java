package pe.foodly.radar.application.events;

import java.time.Instant;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;

/** Evento de integración de Business: cambió el estado operativo (abierto o cerrado) del huarique (5.3.4). */
public record OpeningStatusChangedEvent(String eventId, String huariqueId, long version, Instant occurredAt,
                                        AvailabilityStatus status) {
}
