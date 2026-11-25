package pe.foodly.radar.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Valida el JWT emitido por IAM (HS256 con clave compartida, firma, expiración, {@code iss} y {@code aud}) y, si es
 * válido, deja al usuario autenticado con su id y su rol (DD-06, QA-1, 5.1.4). Un token ausente o inválido no se
 * rechaza aquí: la petición sigue sin autenticar y decide la configuración de rutas (401 en las protegidas, acceso
 * normal en las públicas).
 *
 * <p>La clave llega en Base64 ({@code JWT_SECRET_BASE64}) y se decodifica con {@link Decoders#BASE64}, igual que
 * IAM. El emisor debe ser exactamente {@link JwtProperties#issuer()} y {@code aud} debe incluir
 * {@link JwtProperties#audience()}.
 *
 * <p>No es un bean: lo crea {@link SecurityConfig} para que solo se ejecute dentro de la cadena de seguridad.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";
    /** HS256 exige una clave de al menos 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtParser parser;
    private final String userIdClaim;
    private final String roleClaim;

    public JwtAuthenticationFilter(JwtProperties properties) {
        this.parser = Jwts.parser()
                .verifyWith(signingKey(properties.secretBase64()))
                .requireIssuer(requireText(properties.issuer(), "JWT_ISSUER"))
                .requireAudience(requireText(properties.audience(), "JWT_AUDIENCE"))
                .build();
        this.userIdClaim = properties.userIdClaim();
        this.roleClaim = properties.roleClaim();
    }

    /** Decodifica la clave como IAM ({@code Decoders.BASE64}) y exige al menos 32 bytes. */
    private static SecretKey signingKey(String secretBase64) {
        if (secretBase64 == null || secretBase64.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET_BASE64 es obligatorio y debe tener al menos " + MIN_SECRET_BYTES + " bytes.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(secretBase64.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("JWT_SECRET_BASE64 debe estar en Base64 válido.", e);
        }
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET_BASE64 es obligatorio y debe tener al menos " + MIN_SECRET_BYTES
                            + " bytes una vez decodificado.");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private static String requireText(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " es obligatorio.");
        }
        return value.trim();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            authenticate(header.substring(BEARER_PREFIX.length()).trim());
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();

            Object userId = claims.get(userIdClaim);
            if (userId == null || userId.toString().isBlank()) {
                log.debug("Token rechazado: no trae el claim '{}'.", userIdClaim);
                return;
            }

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    userId.toString(), null, authoritiesOf(claims.get(roleClaim))));
            SecurityContextHolder.setContext(context);
        } catch (JwtException | IllegalArgumentException e) {
            // Nunca se registra el token, solo el tipo de fallo (firma, expiración, iss o aud).
            log.debug("Token rechazado: {}", e.getClass().getSimpleName());
        }
    }

    /** El rol puede venir como texto o como lista de textos. */
    private static List<GrantedAuthority> authoritiesOf(Object roleClaimValue) {
        Collection<?> roles;
        if (roleClaimValue == null) {
            roles = List.of();
        } else if (roleClaimValue instanceof Collection<?> collection) {
            roles = collection;
        } else {
            roles = List.of(roleClaimValue);
        }
        return roles.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .map(role -> role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }
}
