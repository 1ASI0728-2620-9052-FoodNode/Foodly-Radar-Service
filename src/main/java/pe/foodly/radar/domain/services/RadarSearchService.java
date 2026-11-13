package pe.foodly.radar.domain.services;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.events.DomainEvent;
import pe.foodly.radar.domain.model.events.H3CellCalculated;
import pe.foodly.radar.domain.model.events.NearbyHuariquesFound;
import pe.foodly.radar.domain.model.events.NoHuariquesFound;
import pe.foodly.radar.domain.model.valueobjects.CoverageZone;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;

/**
 * Domain Service (5.2.1, 5.2.7.1): calcula la zona de cobertura del explorador, obtiene los candidatos,
 * deja solo los activos dentro de la zona y los ordena por distancia ascendente.
 *
 * <p>Los domain events se registran durante {@link #search} y la capa de aplicación los recoge con
 * {@link #pullDomainEvents()} para publicarlos. El registro es por hilo, de modo que el servicio puede
 * compartirse entre peticiones concurrentes. Cada {@code search} descarta los eventos que no se hayan
 * recogido de la búsqueda anterior en el mismo hilo.
 */
public class RadarSearchService {

    private final IHuariqueLocationRepository repository;
    private final IH3IndexService h3IndexService;
    private final Clock clock;
    private final ThreadLocal<List<DomainEvent>> recordedEvents = ThreadLocal.withInitial(ArrayList::new);

    public RadarSearchService(IHuariqueLocationRepository repository, IH3IndexService h3IndexService) {
        this(repository, h3IndexService, Clock.systemUTC());
    }

    public RadarSearchService(IHuariqueLocationRepository repository, IH3IndexService h3IndexService,
                              Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.h3IndexService = Objects.requireNonNull(h3IndexService);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<HuariqueLocation> search(GeoCoordinates coordinates, int kRing) {
        Objects.requireNonNull(coordinates, "Las coordenadas son obligatorias.");
        if (kRing < 0) {
            throw new IllegalArgumentException("El k-ring no puede ser negativo.");
        }

        List<DomainEvent> events = recordedEvents.get();
        events.clear();

        H3Cell origin = h3IndexService.toCell(coordinates);
        events.add(new H3CellCalculated(coordinates, origin, now()));

        CoverageZone zone = h3IndexService.kRing(origin, kRing);
        List<HuariqueLocation> candidates = repository.findByCells(zone.cells());

        // Un mismo huarique podría llegar dos veces si está transitoriamente en dos sets: se deja uno.
        Map<String, HuariqueLocation> unique = new LinkedHashMap<>();
        for (HuariqueLocation candidate : candidates) {
            if (candidate.isActive() && zone.contains(candidate.getH3Cell())) {
                unique.putIfAbsent(candidate.getHuariqueId().value(), candidate);
            }
        }

        List<HuariqueLocation> result = unique.values().stream()
                .map(location -> new Ranked(location, coordinates.distanceTo(location.getCoordinates()).meters()))
                .sorted(Comparator.comparingDouble(Ranked::meters)
                        .thenComparing(ranked -> ranked.location().getHuariqueId().value()))
                .map(Ranked::location)
                .toList();

        if (result.isEmpty()) {
            events.add(new NoHuariquesFound(coordinates, kRing, now()));
        } else {
            events.add(new NearbyHuariquesFound(coordinates, kRing, result.size(), now()));
        }
        return result;
    }

    /** Devuelve y vacía los eventos registrados por la última búsqueda de este hilo. */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = recordedEvents.get();
        List<DomainEvent> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private record Ranked(HuariqueLocation location, double meters) {
    }
}
