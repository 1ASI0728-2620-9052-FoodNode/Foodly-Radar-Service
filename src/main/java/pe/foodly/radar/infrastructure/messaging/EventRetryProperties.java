package pe.foodly.radar.infrastructure.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Reintento en memoria al procesar un evento. Si se agotan los intentos, el mensaje va a la Dead Letter Queue
 * (5.2.4: «si el procesamiento falla tres veces»).
 */
@ConfigurationProperties(prefix = "radar.events")
public record EventRetryProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("200") long initialBackoffMs,
        @DefaultValue("2.0") double backoffMultiplier) {

    public EventRetryProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("radar.events.max-attempts debe ser al menos 1.");
        }
        if (initialBackoffMs < 0) {
            throw new IllegalArgumentException("radar.events.initial-backoff-ms no puede ser negativo.");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("radar.events.backoff-multiplier debe ser al menos 1.");
        }
    }
}
