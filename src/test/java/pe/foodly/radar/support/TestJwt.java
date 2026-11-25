package pe.foodly.radar.support;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;

/**
 * Genera JWT como los emite IAM: HS256 con la clave compartida, {@code iss=foodly-iam}, {@code aud} con todos los
 * destinatarios de Foodly, {@code sub}, {@code role}, {@code iat} y {@code exp}. La clave se entrega en Base64
 * ({@link #SECRET_BASE64}) y se firma con los bytes decodificados, igual que {@code JwtTokenIssuer}.
 */
public final class TestJwt {

    /**
     * Lo que se configura en {@code JWT_SECRET_BASE64}: Base64 de 46 bytes (literal, para poder usarlo en
     * anotaciones). Solo para pruebas.
     */
    public static final String SECRET_BASE64 = "dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtMDEyMzQ1Njc4OQ==";

    /** Los bytes de la clave, decodificados igual que lo hace IAM. */
    private static final byte[] KEY_BYTES = Base64.getDecoder().decode(SECRET_BASE64);
    public static final String ISSUER = "foodly-iam";
    public static final String AUDIENCE = "foodly-radar";
    /** Los destinatarios que IAM emite por defecto. */
    public static final List<String> IAM_AUDIENCES = List.of(
            "foodly-iam", "foodly-business", "foodly-radar", "foodly-messaging", "foodly-reservation",
            "foodly-community");
    public static final String EXPLORER = "GASTRONOMIC_EXPLORER";
    public static final String OWNER = "HUARIQUE_OWNER";

    private TestJwt() {
    }

    public static String explorer() {
        return token("user-1", EXPLORER);
    }

    public static String owner() {
        return token("user-2", OWNER);
    }

    public static String token(String userId, String role) {
        return build("sub", userId, "role", role, Duration.ofMinutes(60), KEY_BYTES, ISSUER, IAM_AUDIENCES);
    }

    public static String expired() {
        return build("sub", "user-1", "role", EXPLORER, Duration.ofMinutes(-5), KEY_BYTES, ISSUER, IAM_AUDIENCES);
    }

    /** Firmado con otra clave (bytes del texto indicado, de al menos 32 caracteres). */
    public static String signedWith(String otherKeyText) {
        return build("sub", "user-1", "role", EXPLORER, Duration.ofMinutes(60),
                otherKeyText.getBytes(StandardCharsets.UTF_8), ISSUER, IAM_AUDIENCES);
    }

    /** Firmado con el texto Base64 usado como bytes UTF-8: el error típico al no decodificar la clave. */
    public static String signedWithTheBase64TextAsKey() {
        return build("sub", "user-1", "role", EXPLORER, Duration.ofMinutes(60),
                SECRET_BASE64.getBytes(StandardCharsets.UTF_8), ISSUER, IAM_AUDIENCES);
    }

    public static String withClaims(String userIdClaim, String userId, String roleClaim, Object role) {
        return build(userIdClaim, userId, roleClaim, role, Duration.ofMinutes(60), KEY_BYTES, ISSUER, IAM_AUDIENCES);
    }

    /** {@code issuer} nulo omite el claim {@code iss}. */
    public static String withIssuer(String issuer) {
        return build("sub", "user-1", "role", EXPLORER, Duration.ofMinutes(60), KEY_BYTES, issuer, IAM_AUDIENCES);
    }

    /** {@code audience} nulo o vacío omite el claim {@code aud}. */
    public static String withAudience(Collection<String> audience) {
        return build("sub", "user-1", "role", EXPLORER, Duration.ofMinutes(60), KEY_BYTES, ISSUER, audience);
    }

    private static String build(String userIdClaim, String userId, String roleClaim, Object role, Duration ttl,
                                byte[] keyBytes, String issuer, Collection<String> audience) {
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        JwtBuilder builder = Jwts.builder()
                .claim(userIdClaim, userId)
                .claim(roleClaim, role)
                .issuedAt(Date.from(Instant.now().minus(Duration.ofMinutes(10))))
                .expiration(Date.from(Instant.now().plus(ttl)));
        if (issuer != null) {
            builder.issuer(issuer);
        }
        if (audience != null && !audience.isEmpty()) {
            builder.audience().add(audience).and();
        }
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }
}
