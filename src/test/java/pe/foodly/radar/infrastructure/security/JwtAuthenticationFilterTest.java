package pe.foodly.radar.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import pe.foodly.radar.support.TestJwt;

class JwtAuthenticationFilterTest {

    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(new JwtProperties(TestJwt.SECRET, "sub", "role"));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Ejecuta el filtro, comprueba que la cadena siguió y devuelve la autenticación que dejó (o null). */
    private Authentication run(JwtAuthenticationFilter filter, String authorizationHeader) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("la cadena debe continuar siempre").isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private Authentication run(String authorizationHeader) throws Exception {
        return run(filter, authorizationHeader);
    }

    @Test
    void validTokenAuthenticatesWithUserIdAndRole() throws Exception {
        Authentication auth = run("Bearer " + TestJwt.explorer());

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getPrincipal()).isEqualTo("user-1");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_GASTRONOMIC_EXPLORER");
    }

    @Test
    void bearerPrefixIsCaseInsensitive() throws Exception {
        assertThat(run("bearer " + TestJwt.explorer())).isNotNull();
    }

    @Test
    void withoutAuthorizationHeaderTheRequestStaysUnauthenticated() throws Exception {
        assertThat(run(null)).isNull();
    }

    @Test
    void nonBearerSchemeIsIgnored() throws Exception {
        assertThat(run("Basic dXNlcjpwYXNz")).isNull();
    }

    @Test
    void garbageTokenIsIgnored() throws Exception {
        assertThat(run("Bearer esto-no-es-un-jwt")).isNull();
        assertThat(run("Bearer ")).isNull();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() throws Exception {
        assertThat(run("Bearer " + TestJwt.signedWith("another-secret-another-secret-another-0123456789"))).isNull();
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        assertThat(run("Bearer " + TestJwt.expired())).isNull();
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = TestJwt.explorer();
        String[] parts = token.split("\\.");
        // Cambia el payload (ahora el rol dice HUARIQUE_OWNER) y deja la firma original.
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"user-1\",\"role\":\"HUARIQUE_OWNER\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(run("Bearer " + parts[0] + "." + forgedPayload + "." + parts[2])).isNull();
    }

    @Test
    void unsignedTokenWithAlgNoneIsRejected() throws Exception {
        String unsigned = Jwts.builder().subject("user-1").claim("role", TestJwt.EXPLORER)
                .expiration(new Date(System.currentTimeMillis() + 60_000)).compact();

        assertThat(run("Bearer " + unsigned)).isNull();
    }

    @Test
    void tokenWithoutTheUserIdClaimIsRejected() throws Exception {
        assertThat(run("Bearer " + TestJwt.withClaims("other", "user-1", "role", TestJwt.EXPLORER))).isNull();
    }

    @Test
    void tokenWithoutRoleAuthenticatesWithNoAuthorities() throws Exception {
        Authentication auth = run("Bearer " + TestJwt.withClaims("sub", "user-1", "other", "x"));

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void claimNamesAreConfigurable() throws Exception {
        JwtAuthenticationFilter custom =
                new JwtAuthenticationFilter(new JwtProperties(TestJwt.SECRET, "uid", "rol"));

        Authentication auth = run(custom, "Bearer " + TestJwt.withClaims("uid", "abc-123", "rol", TestJwt.OWNER));

        assertThat(auth.getPrincipal()).isEqualTo("abc-123");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_HUARIQUE_OWNER");
        // Con los nombres por defecto, ese mismo token no tiene el claim de usuario.
        assertThat(run("Bearer " + TestJwt.withClaims("uid", "abc-123", "rol", TestJwt.OWNER))).isNull();
    }

    @Test
    void roleClaimMayBeAListAndMayAlreadyHaveTheRolePrefix() throws Exception {
        Authentication auth = run("Bearer "
                + TestJwt.withClaims("sub", "user-1", "role", List.of("ROLE_GASTRONOMIC_EXPLORER", "huarique_owner")));

        assertThat(auth.getAuthorities()).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_GASTRONOMIC_EXPLORER", "ROLE_HUARIQUE_OWNER");
    }

    @Test
    void aMissingOrShortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtAuthenticationFilter(new JwtProperties(null, "sub", "role")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtAuthenticationFilter(new JwtProperties("corto", "sub", "role")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
    }
}
