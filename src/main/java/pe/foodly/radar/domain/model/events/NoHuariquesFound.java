package pe.foodly.radar.domain.model.events;

import java.time.Instant;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;

/** Se emite cuando la zona de cobertura no tiene huariques (5.2.1). */
public record NoHuariquesFound(GeoCoordinates coordinates, int kRing, Instant occurredAt)
        implements DomainEvent {
}
