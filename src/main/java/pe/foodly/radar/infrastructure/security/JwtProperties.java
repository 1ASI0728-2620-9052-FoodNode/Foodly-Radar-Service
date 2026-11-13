package pe.foodly.radar.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del JWT de IAM (5.1.4). El secreto es el mismo que usa IAM para firmar y se toma de
 * {@code JWT_SECRET}; los nombres de los claims, de {@code JWT_USER_ID_CLAIM} y {@code JWT_ROLE_CLAIM}.
 */
@ConfigurationProperties(prefix = "radar.security.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("sub") String userIdClaim,
        @DefaultValue("role") String roleClaim) {
}
