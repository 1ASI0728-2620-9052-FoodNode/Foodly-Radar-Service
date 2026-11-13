package pe.foodly.radar.domain.model.valueobjects;

import java.util.Objects;
import java.util.Set;

/** Conjunto de celdas H3 (celda central más k-ring) donde se busca (5.2.1). Es inmutable. */
public record CoverageZone(Set<H3Cell> cells) {

    public CoverageZone {
        Objects.requireNonNull(cells, "Las celdas de la zona de cobertura son obligatorias.");
        cells = Set.copyOf(cells);
    }

    public boolean contains(H3Cell cell) {
        return cells.contains(cell);
    }
}
