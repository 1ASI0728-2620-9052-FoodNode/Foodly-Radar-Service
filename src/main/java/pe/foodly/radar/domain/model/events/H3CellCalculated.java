package pe.foodly.radar.domain.model.events;

import java.time.Instant;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;

/** Se emite cuando se calcula la celda H3 del explorador (5.2.1). */
public record H3CellCalculated(GeoCoordinates coordinates, H3Cell cell, Instant occurredAt)
        implements DomainEvent {
}
