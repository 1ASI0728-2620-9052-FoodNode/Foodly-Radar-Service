package pe.foodly.radar.infrastructure.h3;

import com.uber.h3core.H3Core;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pe.foodly.radar.domain.model.valueobjects.CoverageZone;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.services.IH3IndexService;

/**
 * Implementa {@link IH3IndexService} con la librería Uber H3 v4 (5.2.4).
 * Usa una resolución fija, configurable con {@code RADAR_H3_RESOLUTION} (por defecto 9).
 */
@Component
public class UberH3IndexService implements IH3IndexService {

    private static final int MIN_RESOLUTION = 0;
    private static final int MAX_RESOLUTION = 15;

    private final H3Core h3;
    private final int resolution;

    public UberH3IndexService(@Value("${radar.h3.resolution}") int resolution) {
        if (resolution < MIN_RESOLUTION || resolution > MAX_RESOLUTION) {
            throw new IllegalArgumentException(
                    "RADAR_H3_RESOLUTION debe estar entre " + MIN_RESOLUTION + " y " + MAX_RESOLUTION + ".");
        }
        this.resolution = resolution;
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo inicializar la librería H3.", e);
        }
    }

    @Override
    public H3Cell toCell(GeoCoordinates coordinates) {
        return new H3Cell(h3.latLngToCellAddress(coordinates.latitude(), coordinates.longitude(), resolution));
    }

    @Override
    public CoverageZone kRing(H3Cell cell, int k) {
        if (k < 0) {
            throw new IllegalArgumentException("El k-ring no puede ser negativo.");
        }
        List<String> addresses = h3.gridDisk(cell.index(), k);
        Set<H3Cell> cells = new HashSet<>();
        for (String address : addresses) {
            cells.add(new H3Cell(address));
        }
        return new CoverageZone(cells);
    }

    public int resolution() {
        return resolution;
    }
}
