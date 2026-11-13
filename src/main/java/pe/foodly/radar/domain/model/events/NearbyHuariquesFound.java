package pe.foodly.radar.domain.model.events;

import java.time.Instant;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;

/** Se emite cuando la búsqueda devuelve al menos un huarique (5.2.1). */
public record NearbyHuariquesFound(GeoCoordinates coordinates, int kRing, int total, Instant occurredAt)
        implements DomainEvent {
}
