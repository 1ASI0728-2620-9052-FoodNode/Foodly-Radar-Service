package pe.foodly.radar.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import java.util.Base64;
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

    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(properties(TestJwt.SECRET_BASE64));

    private static JwtProperties properties(String secretBase64) {
        return new JwtProperties(secretBase64, TestJwt.ISSUER, TestJwt.AUDIENCE, "sub", "role");
    }

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
    void tokenSignedWithTheBase64TextAsKeyIsRejected() throws Exception {
        // La clave se decodifica de Base64: firmar con el texto Base64 como bytes UTF-8 no vale.
        assertThat(run("Bearer " + TestJwt.signedWithTheBase64TextAsKey())).isNull();
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
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"user-1\",\"role\":\"HUARIQUE_OWNER\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(run("Bearer " + parts[0] + "." + forgedPayload + "." + parts[2])).isNull();
    }

    @Test
    void unsignedTokenWithAlgNoneIsRejected() throws Exception {
        String unsigned = Jwts.builder().subject("user-1").claim("role", TestJwt.EXPLORER)
                .issuer(TestJwt.ISSUER).audience().add(TestJwt.AUDIENCE).and()
                .expiration(new Date(System.currentTimeMillis() + 60_000)).compact();

        assertThat(run("Bearer " + unsigned)).isNull();
    }

    // ---- iss y aud (contrato JWT v1 de IAM) ----

    @Test
    void tokenWithAnotherIssuerIsRejected() throws Exception {
        assertThat(run("Bearer " + TestJwt.withIssuer("otro-emisor"))).isNull();
    }

    @Test
    void tokenWithoutIssuerIsRejected() throws Exception {
        assertThat(run("Bearer " + TestJwt.withIssuer(null))).isNull();
    }

    @Test
    void tokenWithoutAudienceIsRejected() throws Exception {
        assertThat(run("Bearer " + TestJwt.withAudience(null))).isNull();
    }

    @Test
    void tokenWhoseAudienceDoesNotIncludeRadarIsRejected() throws Exception {
        assertThat(run("Bearer " + TestJwt.withAudience(List.of("foodly-iam", "foodly-business")))).isNull();
    }

    @Test
    void tokenWhoseAudienceIncludesRadarAmongOthersIsAccepted() throws Exception {
        assertThat(run("Bearer " + TestJwt.withAudience(List.of("foodly-business", "foodly-radar")))).isNotNull();
        assertThat(run("Bearer " + TestJwt.withAudience(List.of("foodly-radar")))).isNotNull();
    }

    @Test
    void issuerAndAudienceAreConfigurable() throws Exception {
        JwtAuthenticationFilter custom = new JwtAuthenticationFilter(
                new JwtProperties(TestJwt.SECRET_BASE64, "otro-emisor", "foodly-messaging", "sub", "role"));

        assertThat(run(custom, "Bearer " + TestJwt.withIssuer("otro-emisor"))).isNotNull();
        // El token por defecto tiene iss=foodly-iam: ya no sirve con ese emisor.
        assertThat(run(custom, "Bearer " + TestJwt.explorer())).isNull();
    }

    // ---- claims de usuario y rol ----

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
        JwtAuthenticationFilter custom = new JwtAuthenticationFilter(
                new JwtProperties(TestJwt.SECRET_BASE64, TestJwt.ISSUER, TestJwt.AUDIENCE, "uid", "rol"));

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

    // ---- la clave: Base64 de al menos 32 bytes ----

    @Test
    void aMissingOrBlankSecretFailsFast() {
        assertThatThrownBy(() -> new JwtAuthenticationFilter(properties(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET_BASE64");
        assertThatThrownBy(() -> new JwtAuthenticationFilter(properties("  ")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET_BASE64");
    }

    @Test
    void aSecretShorterThan32DecodedBytesFailsFast() {
        String thirtyOneBytes = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> new JwtAuthenticationFilter(properties(thirtyOneBytes)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }

    @Test
    void aSecretOfExactly32DecodedBytesIsAccepted() {
        String thirtyTwoBytes = Base64.getEncoder().encodeToString(new byte[32]);

        assertThat(new JwtAuthenticationFilter(properties(thirtyTwoBytes))).isNotNull();
    }

    @Test
    void aSecretThatIsNotBase64FailsFast() {
        assertThatThrownBy(() -> new JwtAuthenticationFilter(properties("esto no es base64 ***")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Base64");
    }

    @Test
    void aBlankIssuerOrAudienceFailsFast() {
        assertThatThrownBy(() -> new JwtAuthenticationFilter(
                new JwtProperties(TestJwt.SECRET_BASE64, " ", TestJwt.AUDIENCE, "sub", "role")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_ISSUER");
        assertThatThrownBy(() -> new JwtAuthenticationFilter(
                new JwtProperties(TestJwt.SECRET_BASE64, TestJwt.ISSUER, null, "sub", "role")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_AUDIENCE");
    }
}
