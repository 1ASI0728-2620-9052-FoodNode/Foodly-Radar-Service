package pe.foodly.radar.application.queryhandlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import pe.foodly.radar.application.exceptions.InvalidRadarSearchException;
import pe.foodly.radar.application.queries.NearbyHuarique;
import pe.foodly.radar.application.queries.SearchNearbyHuariquesQuery;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.events.DomainEvent;
import pe.foodly.radar.domain.model.events.H3CellCalculated;
import pe.foodly.radar.domain.model.events.NoHuariquesFound;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.services.RadarSearchService;

@ExtendWith(MockitoExtension.class)
class SearchNearbyHuariquesQueryHandlerTest {

    private static final int MAX_K_RING = 5;
    private static final GeoCoordinates ORIGIN = new GeoCoordinates(-12.1219, -77.0297);

    @Mock
    private RadarSearchService radarSearchService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SearchNearbyHuariquesQueryHandler handler() {
        return new SearchNearbyHuariquesQueryHandler(radarSearchService, eventPublisher, MAX_K_RING);
    }

    private HuariqueLocation location(String id, double lat, double lng) {
        return new HuariqueLocation(new HuariqueId(id), new GeoCoordinates(lat, lng), new H3Cell("cell"),
                AvailabilityStatus.ACTIVE);
    }

    @Test
    void returnsTheResultsKeepingTheDomainOrderWithTheirDistance() {
        HuariqueLocation near = location("near", -12.1229, -77.0297);
        HuariqueLocation far = location("far", -12.1498, -77.0207);
        when(radarSearchService.search(ORIGIN, 2)).thenReturn(List.of(near, far));
        when(radarSearchService.pullDomainEvents()).thenReturn(List.of());

        List<NearbyHuarique> result = handler().handle(new SearchNearbyHuariquesQuery(-12.1219, -77.0297, 2));

        assertThat(result).extracting(r -> r.location().getHuariqueId().value()).containsExactly("near", "far");
        assertThat(result.get(0).distance().meters()).isCloseTo(111.2, within(0.5));
        assertThat(result.get(1).distance().meters()).isCloseTo(3252.96, within(1.0));
    }

    @Test
    void returnsAnEmptyListWhenTheZoneHasNoHuariques() {
        when(radarSearchService.search(ORIGIN, 1)).thenReturn(List.of());
        when(radarSearchService.pullDomainEvents()).thenReturn(List.of());

        assertThat(handler().handle(new SearchNearbyHuariquesQuery(-12.1219, -77.0297, 1))).isEmpty();
    }

    @Test
    void publishesEveryDomainEventTheServiceRegisteredAfterTheSearch() {
        Instant now = Instant.parse("2026-11-13T12:00:00Z");
        DomainEvent calculated = new H3CellCalculated(ORIGIN, new H3Cell("cell"), now);
        DomainEvent none = new NoHuariquesFound(ORIGIN, 1, now);
        when(radarSearchService.search(ORIGIN, 1)).thenReturn(List.of());
        when(radarSearchService.pullDomainEvents()).thenReturn(List.of(calculated, none));

        handler().handle(new SearchNearbyHuariquesQuery(-12.1219, -77.0297, 1));

        InOrder order = inOrder(radarSearchService, eventPublisher);
        order.verify(radarSearchService).search(ORIGIN, 1);
        order.verify(radarSearchService).pullDomainEvents();
        order.verify(eventPublisher).publishEvent(calculated);
        order.verify(eventPublisher).publishEvent(none);
    }

    @Test
    void acceptsTheBoundsOfTheKRingRange() {
        when(radarSearchService.search(ORIGIN, 0)).thenReturn(List.of());
        when(radarSearchService.search(ORIGIN, MAX_K_RING)).thenReturn(List.of());
        when(radarSearchService.pullDomainEvents()).thenReturn(List.of());

        handler().handle(new SearchNearbyHuariquesQuery(-12.1219, -77.0297, 0));
        handler().handle(new SearchNearbyHuariquesQuery(-12.1219, -77.0297, MAX_K_RING));

        verify(radarSearchService).search(ORIGIN, 0);
        verify(radarSearchService).search(ORIGIN, MAX_K_RING);
    }

    @Test
    void rejectsAKRingAboveTheConfiguredMaximum() {
        assertThatThrownBy(() -> handler().handle(new SearchNearbyHuariquesQuery(-12.1219, -77.0297, 6)))
                .isInstanceOf(InvalidRadarSearchException.class)
                .hasMessage("el k-ring debe estar entre 0 y 5");

        verifyNoInteractions(radarSearchService, eventPublisher);
    }

    @Test
    void rejectsANegativeKRing() {
        assertThatThrownBy(() -> handler().handle(new SearchNearbyHuariquesQuery(-12.1219, -77.0297, -1)))
                .isInstanceOf(InvalidRadarSearchException.class)
                .hasMessage("el k-ring debe estar entre 0 y 5");

        verify(radarSearchService, never()).search(any(), anyInt());
    }

    @Test
    void turnsInvalidCoordinatesIntoAnInvalidSearchWithALowercaseDetail() {
        assertThatThrownBy(() -> handler().handle(new SearchNearbyHuariquesQuery(91, -77.0297, 1)))
                .isInstanceOf(InvalidRadarSearchException.class)
                .hasMessage("la latitud debe estar entre -90 y 90");
        assertThatThrownBy(() -> handler().handle(new SearchNearbyHuariquesQuery(-12.1219, 181, 1)))
                .isInstanceOf(InvalidRadarSearchException.class)
                .hasMessage("la longitud debe estar entre -180 y 180");

        verifyNoInteractions(radarSearchService, eventPublisher);
    }
}
