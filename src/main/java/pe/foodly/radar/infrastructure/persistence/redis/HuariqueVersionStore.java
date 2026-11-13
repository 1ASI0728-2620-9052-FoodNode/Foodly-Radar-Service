package pe.foodly.radar.infrastructure.persistence.redis;

import java.util.Optional;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;

/**
 * Versión del último evento de Business aplicada a un huarique. Es una concesión de infraestructura
 * (no forma parte del dominio): el listener la usa para descartar eventos repetidos o desordenados.
 */
public interface HuariqueVersionStore {

    /** Versión almacenada, o vacío si el huarique no existe o aún no tiene versión. */
    Optional<Long> findVersion(HuariqueId id);

    /** Guarda la versión en el hash del huarique. No hace nada si el huarique no está indexado. */
    void writeVersion(HuariqueId id, long version);
}
