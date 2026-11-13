package pe.foodly.radar.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

/** Genera JWT como lo haría IAM: HMAC con el secreto compartido. */
public final class TestJwt {

    public static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
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
        return builder(userId, "role", role, Duration.ofMinutes(60), SECRET);
    }

    public static String expired() {
        return builder("user-1", "role", EXPLORER, Duration.ofMinutes(-5), SECRET);
    }

    public static String signedWith(String otherSecret) {
        return builder("user-1", "role", EXPLORER, Duration.ofMinutes(60), otherSecret);
    }

    public static String withClaims(String userIdClaim, String userId, String roleClaim, Object role) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .claim(userIdClaim, userId)
                .claim(roleClaim, role)
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(60))))
                .signWith(key)
                .compact();
    }

    private static String builder(String userId, String roleClaim, String role, Duration ttl, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .claim(roleClaim, role)
                .issuedAt(Date.from(Instant.now().minus(Duration.ofMinutes(10))))
                .expiration(Date.from(Instant.now().plus(ttl)))
                .signWith(key)
                .compact();
    }
}
