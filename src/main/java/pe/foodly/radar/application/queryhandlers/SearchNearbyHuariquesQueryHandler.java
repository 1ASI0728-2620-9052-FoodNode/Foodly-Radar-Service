package pe.foodly.radar.application.queryhandlers;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import pe.foodly.radar.application.exceptions.InvalidRadarSearchException;
import pe.foodly.radar.application.queries.NearbyHuarique;
import pe.foodly.radar.application.queries.SearchNearbyHuariquesQuery;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.services.RadarSearchService;

/**
 * Crea las coordenadas, delega la búsqueda en {@link RadarSearchService}, calcula la distancia de cada
 * resultado y publica los domain events que el servicio registró (5.2.3).
 */
@Component
public class SearchNearbyHuariquesQueryHandler {

    private final RadarSearchService radarSearchService;
    private final ApplicationEventPublisher eventPublisher;
    private final int maxKRing;

    public SearchNearbyHuariquesQueryHandler(RadarSearchService radarSearchService,
                                             ApplicationEventPublisher eventPublisher,
                                             @Value("${radar.max-k-ring}") int maxKRing) {
        this.radarSearchService = radarSearchService;
        this.eventPublisher = eventPublisher;
        this.maxKRing = maxKRing;
    }

    public List<NearbyHuarique> handle(SearchNearbyHuariquesQuery query) {
        GeoCoordinates coordinates = coordinatesOf(query);
        if (query.kRing() < 0 || query.kRing() > maxKRing) {
            throw new InvalidRadarSearchException("el k-ring debe estar entre 0 y " + maxKRing);
        }

        List<HuariqueLocation> found = radarSearchService.search(coordinates, query.kRing());
        radarSearchService.pullDomainEvents().forEach(eventPublisher::publishEvent);

        return found.stream()
                .map(location -> new NearbyHuarique(location, coordinates.distanceTo(location.getCoordinates())))
                .toList();
    }

    private static GeoCoordinates coordinatesOf(SearchNearbyHuariquesQuery query) {
        try {
            return new GeoCoordinates(query.latitude(), query.longitude());
        } catch (IllegalArgumentException e) {
            String detail = e.getMessage();
            throw new InvalidRadarSearchException(
                    Character.toLowerCase(detail.charAt(0)) + detail.substring(1).replaceAll("\\.$", ""));
        }
    }
}
