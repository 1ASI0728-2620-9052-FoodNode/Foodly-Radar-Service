package pe.foodly.radar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pe.foodly.radar.support.TestJwt;

/** Defaults de application.yml (sin variables de entorno), sin levantar Redis ni RabbitMQ. */
class ApplicationYamlDefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void theDefaultPortIs8082() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty("server.port")).isEqualTo("8082"));
    }

    @Test
    void jwtDefaultsMatchTheIamContract() {
        runner.withPropertyValues("JWT_SECRET_BASE64=" + TestJwt.SECRET_BASE64).run(context -> {
            var environment = context.getEnvironment();

            assertThat(environment.getProperty("radar.security.jwt.secret-base64")).isEqualTo(TestJwt.SECRET_BASE64);
            assertThat(environment.getProperty("radar.security.jwt.issuer")).isEqualTo("foodly-iam");
            assertThat(environment.getProperty("radar.security.jwt.audience")).isEqualTo("foodly-radar");
            assertThat(environment.getProperty("radar.security.jwt.user-id-claim")).isEqualTo("sub");
            assertThat(environment.getProperty("radar.security.jwt.role-claim")).isEqualTo("role");
        });
    }

    @Test
    void theBusinessExchangeDefaultsToFoodlyBusiness() {
        runner.run(context ->
                assertThat(context.getEnvironment().getProperty("radar.events.exchange")).isEqualTo("foodly.business"));
    }

    @Test
    void jwtIssuerAudienceAndExchangeCanBeOverriddenByEnvironment() {
        runner.withPropertyValues("JWT_ISSUER=otro-iam", "JWT_AUDIENCE=otro-radar", "BUSINESS_EVENTS_EXCHANGE=otro.exchange",
                "SERVER_PORT=9999").run(context -> {
            var environment = context.getEnvironment();

            assertThat(environment.getProperty("radar.security.jwt.issuer")).isEqualTo("otro-iam");
            assertThat(environment.getProperty("radar.security.jwt.audience")).isEqualTo("otro-radar");
            assertThat(environment.getProperty("radar.events.exchange")).isEqualTo("otro.exchange");
            assertThat(environment.getProperty("server.port")).isEqualTo("9999");
        });
    }
}
