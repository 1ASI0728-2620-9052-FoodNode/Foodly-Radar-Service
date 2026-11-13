package pe.foodly.radar.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Comprueba cómo Spring Boot interpreta RABBITMQ_URL (sin conectarse a ningún broker). */
class RabbitUrlConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class));

    @Test
    void cloudAmqpUrlWithTlsEnablesSslOnPort5671WithTheVirtualHost() {
        runner.withPropertyValues("spring.rabbitmq.addresses=amqps://usuario:clave@lively-owl.rmq.cloudamqp.com/mi-vhost")
                .run(context -> {
                    CachingConnectionFactory factory = context.getBean(CachingConnectionFactory.class);

                    assertThat(factory.getHost()).isEqualTo("lively-owl.rmq.cloudamqp.com");
                    assertThat(factory.getPort()).isEqualTo(5671);
                    assertThat(factory.getUsername()).isEqualTo("usuario");
                    assertThat(factory.getVirtualHost()).isEqualTo("mi-vhost");
                    assertThat(factory.getRabbitConnectionFactory().isSSL()).isTrue();
                });
    }

    @Test
    void plainUrlUsesPort5672WithoutTls() {
        runner.withPropertyValues("spring.rabbitmq.addresses=amqp://guest:guest@localhost:5672")
                .run(context -> {
                    CachingConnectionFactory factory = context.getBean(CachingConnectionFactory.class);

                    assertThat(factory.getHost()).isEqualTo("localhost");
                    assertThat(factory.getPort()).isEqualTo(5672);
                    assertThat(factory.getUsername()).isEqualTo("guest");
                    assertThat(factory.getRabbitConnectionFactory().isSSL()).isFalse();
                });
    }
}
