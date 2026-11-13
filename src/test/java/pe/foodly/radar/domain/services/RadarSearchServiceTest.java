package pe.foodly.radar.domain.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.events.DomainEvent;
import pe.foodly.radar.domain.model.events.H3CellCalculated;
import pe.foodly.radar.domain.model.events.NearbyHuariquesFound;
import pe.foodly.radar.domain.model.events.NoHuariquesFound;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.CoverageZone;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;

@ExtendWith(MockitoExtension.class)
class RadarSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final int K_RING = 1;

    private final GeoCoordinates origin = new GeoCoordinates(-12.1219, -77.0297);
    private final H3Cell originCell = new H3Cell("cell-origin");
    private final H3Cell neighborCell = new H3Cell("cell-neighbor");
    private final H3Cell farCell = new H3Cell("cell-outside-zone");
    private final CoverageZone zone = new CoverageZone(Set.of(originCell, neighborCell));

    @Mock
    private IHuariqueLocationRepository repository;
    @Mock
    private IH3IndexService h3IndexService;

    private RadarSearchService service;

    @BeforeEach
    void setUp() {
        service = new RadarSearchService(repository, h3IndexService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void givenZoneCandidates(HuariqueLocation... candidates) {
        when(h3IndexService.toCell(origin)).thenReturn(originCell);
        when(h3IndexService.kRing(originCell, K_RING)).thenReturn(zone);
        when(repository.findByCells(zone.cells())).thenReturn(List.of(candidates));
    }

    private HuariqueLocation location(String id, double lat, double lng, H3Cell cell,
                                      AvailabilityStatus status) {
        return new HuariqueLocation(new HuariqueId(id), new GeoCoordinates(lat, lng), cell, status);
    }

    @Test
    void excludesInactiveHuariques() {
        HuariqueLocation active = location("active", -12.1229, -77.0297, originCell, AvailabilityStatus.ACTIVE);
        HuariqueLocation inactive = location("inactive", -12.1222, -77.0297, originCell, AvailabilityStatus.INACTIVE);
        givenZoneCandidates(inactive, active);

        List<HuariqueLocation> result = service.search(origin, K_RING);

        assertThat(result).containsExactly(active);
    }

    @Test
    void ordersByAscendingDistance() {
        // Distancias aproximadas al origen: 111 m, 556 m y 3,25 km.
        HuariqueLocation near = location("near", -12.1229, -77.0297, originCell, AvailabilityStatus.ACTIVE);
        HuariqueLocation middle = location("middle", -12.1269, -77.0297, neighborCell, AvailabilityStatus.ACTIVE);
        HuariqueLocation far = location("far", -12.1498, -77.0207, originCell, AvailabilityStatus.ACTIVE);
        givenZoneCandidates(far, near, middle);

        List<HuariqueLocation> result = service.search(origin, K_RING);

        assertThat(result).containsExactly(near, middle, far);
    }

    @Test
    void breaksDistanceTiesByHuariqueIdForADeterministicOrder() {
        HuariqueLocation b = location("b", -12.1229, -77.0297, originCell, AvailabilityStatus.ACTIVE);
        HuariqueLocation a = location("a", -12.1229, -77.0297, originCell, AvailabilityStatus.ACTIVE);
        givenZoneCandidates(b, a);

        assertThat(service.search(origin, K_RING)).containsExactly(a, b);
    }

    @Test
    void returnsEmptyListAndRegistersNoHuariquesFoundWhenThereAreNoCandidates() {
        givenZoneCandidates();

        List<HuariqueLocation> result = service.search(origin, K_RING);
        List<DomainEvent> events = service.pullDomainEvents();

        assertThat(result).isEmpty();
        assertThat(events).containsExactly(
                new H3CellCalculated(origin, originCell, NOW),
                new NoHuariquesFound(origin, K_RING, NOW));
    }

    @Test
    void registersNoHuariquesFoundWhenAllCandidatesAreInactive() {
        givenZoneCandidates(location("inactive", -12.1229, -77.0297, originCell, AvailabilityStatus.INACTIVE));

        List<HuariqueLocation> result = service.search(origin, K_RING);

        assertThat(result).isEmpty();
        assertThat(service.pullDomainEvents()).containsExactly(
                new H3CellCalculated(origin, originCell, NOW),
                new NoHuariquesFound(origin, K_RING, NOW));
    }

    @Test
    void registersNearbyHuariquesFoundWithTheTotalWhenThereAreResults() {
        givenZoneCandidates(
                location("one", -12.1229, -77.0297, originCell, AvailabilityStatus.ACTIVE),
                location("two", -12.1269, -77.0297, neighborCell, AvailabilityStatus.ACTIVE),
                location("off", -12.1222, -77.0297, originCell, AvailabilityStatus.INACTIVE));

        service.search(origin, K_RING);

        assertThat(service.pullDomainEvents()).containsExactly(
                new H3CellCalculated(origin, originCell, NOW),
                new NearbyHuariquesFound(origin, K_RING, 2, NOW));
    }

    @Test
    void ignoresCandidatesWhoseCellIsOutsideTheCoverageZone() {
        HuariqueLocation inside = location("inside", -12.1229, -77.0297, originCell, AvailabilityStatus.ACTIVE);
        HuariqueLocation outside = location("outside", -12.1222, -77.0297, farCell, AvailabilityStatus.ACTIVE);
        givenZoneCandidates(outside, inside);

        assertThat(service.search(origin, K_RING)).containsExactly(inside);
    }

    @Test
    void keepsASingleEntryWhenTheRepositoryReturnsTheSameHuariqueTwice() {
        HuariqueLocation first = location("dup", -12.1229, -77.0297, originCell, AvailabilityStatus.ACTIVE);
        HuariqueLocation second = location("dup", -12.1229, -77.0297, neighborCell, AvailabilityStatus.ACTIVE);
        givenZoneCandidates(first, second);

        assertThat(service.search(origin, K_RING)).hasSize(1);
    }

    @Test
    void pullDomainEventsEmptiesTheRegister() {
        givenZoneCandidates();
        service.search(origin, K_RING);

        assertThat(service.pullDomainEvents()).hasSize(2);
        assertThat(service.pullDomainEvents()).isEmpty();
    }

    @Test
    void aNewSearchReplacesTheEventsOfTheLastOne() {
        givenZoneCandidates();
        service.search(origin, K_RING);
        service.search(origin, K_RING);

        assertThat(service.pullDomainEvents()).hasSize(2);
    }

    @Test
    void rejectsNegativeKRingWithoutTouchingTheCollaborators() {
        assertThatThrownBy(() -> service.search(origin, -1)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository, h3IndexService);
    }
}
