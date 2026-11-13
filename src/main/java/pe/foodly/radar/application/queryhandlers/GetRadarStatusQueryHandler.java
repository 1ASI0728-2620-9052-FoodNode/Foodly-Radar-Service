package pe.foodly.radar.application.queryhandlers;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;
import pe.foodly.radar.application.queries.RadarServiceStatus;

/**
 * Devuelve el estado operativo del servicio (5.2.3), apoyado en Actuator (5.2.4).
 * Radar está operativo cuando puede atender búsquedas, es decir, cuando Redis responde. No depende del
 * broker: si RabbitMQ cae, las búsquedas siguen funcionando y los eventos esperan en la cola (QA-3).
 */
@Component
public class GetRadarStatusQueryHandler {

    private static final String REDIS_COMPONENT = "redis";

    private final HealthEndpoint healthEndpoint;

    public GetRadarStatusQueryHandler(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    public RadarServiceStatus handle() {
        var redis = healthEndpoint.healthForPath(REDIS_COMPONENT);
        boolean up = redis != null && Status.UP.equals(redis.getStatus());
        return up ? RadarServiceStatus.UP : RadarServiceStatus.DOWN;
    }
}
