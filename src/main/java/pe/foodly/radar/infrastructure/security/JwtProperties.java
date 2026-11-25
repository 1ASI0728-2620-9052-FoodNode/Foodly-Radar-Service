package pe.foodly.radar.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del JWT de IAM (5.1.4).
 *
 * <ul>
 *   <li>{@code secretBase64} ({@code JWT_SECRET_BASE64}): la misma clave que usa IAM para firmar, en Base64 estándar.
 *       Se decodifica a bytes (mínimo 32), igual que {@code JwtTokenIssuer} de IAM.</li>
 *   <li>{@code issuer} ({@code JWT_ISSUER}): valor exacto que debe traer el claim {@code iss}.</li>
 *   <li>{@code audience} ({@code JWT_AUDIENCE}): valor que debe estar incluido en el claim {@code aud}.</li>
 *   <li>Los nombres de los claims de usuario y rol, de {@code JWT_USER_ID_CLAIM} y {@code JWT_ROLE_CLAIM}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "radar.security.jwt")
public record JwtProperties(
        String secretBase64,
        @DefaultValue("foodly-iam") String issuer,
        @DefaultValue("foodly-radar") String audience,
        @DefaultValue("sub") String userIdClaim,
        @DefaultValue("role") String roleClaim) {
}
