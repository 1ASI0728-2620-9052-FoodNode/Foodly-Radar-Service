package pe.foodly.radar.domain.repositories;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;

/** Contrato de persistencia de {@link HuariqueLocation}, sin depender de Redis (5.2.1, 5.2.7.1). */
public interface IHuariqueLocationRepository {

    /** Upsert idempotente: reemplaza el registro anterior del mismo huarique. */
    void save(HuariqueLocation location);

    void remove(HuariqueId id);

    Optional<HuariqueLocation> findById(HuariqueId id);

    /** Huariques que pertenecen a alguna de las celdas indicadas. */
    List<HuariqueLocation> findByCells(Set<H3Cell> cells);
}
