package pe.foodly.radar.domain.services;

import pe.foodly.radar.domain.model.valueobjects.CoverageZone;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;

/**
 * Puerto del dominio para la indexación hexagonal. La implementación con Uber H3
 * pertenece a Infrastructure (5.2.1, 5.2.4).
 */
public interface IH3IndexService {

    H3Cell toCell(GeoCoordinates coordinates);

    /** Celda central más las celdas hasta {@code k} anillos alrededor. */
    CoverageZone kRing(H3Cell cell, int k);
}
