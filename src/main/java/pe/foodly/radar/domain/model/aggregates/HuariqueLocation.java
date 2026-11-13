package pe.foodly.radar.domain.model.aggregates;

import java.util.Objects;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;

/**
 * Aggregate Root: ubicación indexada de un huarique dentro del radar (5.2.1, 5.2.7.1).
 * La celda H3 la calcula quien llama (con IH3IndexService) y se pasa a {@link #updateLocation}.
 */
public class HuariqueLocation {

    private final HuariqueId huariqueId;
    private GeoCoordinates coordinates;
    private H3Cell h3Cell;
    private AvailabilityStatus status;

    public HuariqueLocation(HuariqueId huariqueId, GeoCoordinates coordinates, H3Cell h3Cell,
                            AvailabilityStatus status) {
        this.huariqueId = Objects.requireNonNull(huariqueId, "El identificador del huarique es obligatorio.");
        this.coordinates = Objects.requireNonNull(coordinates, "Las coordenadas son obligatorias.");
        this.h3Cell = Objects.requireNonNull(h3Cell, "La celda H3 es obligatoria.");
        this.status = Objects.requireNonNull(status, "El estado es obligatorio.");
    }

    public void updateLocation(GeoCoordinates coordinates, H3Cell h3Cell) {
        this.coordinates = Objects.requireNonNull(coordinates, "Las coordenadas son obligatorias.");
        this.h3Cell = Objects.requireNonNull(h3Cell, "La celda H3 es obligatoria.");
    }

    public void changeStatus(AvailabilityStatus status) {
        this.status = Objects.requireNonNull(status, "El estado es obligatorio.");
    }

    public boolean isActive() {
        return status == AvailabilityStatus.ACTIVE;
    }

    public HuariqueId getHuariqueId() {
        return huariqueId;
    }

    public GeoCoordinates getCoordinates() {
        return coordinates;
    }

    public H3Cell getH3Cell() {
        return h3Cell;
    }

    public AvailabilityStatus getStatus() {
        return status;
    }

    /** La identidad del agregado es el id del huarique. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HuariqueLocation other)) {
            return false;
        }
        return huariqueId.equals(other.huariqueId);
    }

    @Override
    public int hashCode() {
        return huariqueId.hashCode();
    }
}
