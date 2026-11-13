package pe.foodly.radar.infrastructure.messaging;

import java.util.Optional;

/**
 * Regla de versión (5.2.4): antes de aplicar un evento se compara su versión con la almacenada en el hash del
 * huarique; si es menor o igual, el evento es un reenvío o llegó desordenado y se descarta.
 */
public final class EventVersionRule {

    private EventVersionRule() {
    }

    /** @param stored versión guardada en el hash; vacía si el huarique no existe o no tiene versión */
    public static boolean isStale(Optional<Long> stored, long eventVersion) {
        return stored.isPresent() && eventVersion <= stored.get();
    }
}
