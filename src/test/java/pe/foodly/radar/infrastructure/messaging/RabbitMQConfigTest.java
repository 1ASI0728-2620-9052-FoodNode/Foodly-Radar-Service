package pe.foodly.radar.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Topología de RabbitMQ para el contrato de Business v1, sin conectarse a ningún broker. */
class RabbitMQConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(RabbitMQConfig.class);

    @Test
    void theExchangeIsBusinessDurableTopicByDefault() {
        runner.run(context -> {
            TopicExchange exchange = context.getBean(TopicExchange.class);

            assertThat(exchange.getName()).isEqualTo("foodly.business");
            assertThat(exchange.isDurable()).isTrue();
            assertThat(exchange.isAutoDelete()).isFalse();
        });
    }

    @Test
    void theExchangeNameIsConfigurable() {
        runner.withPropertyValues("radar.events.exchange=foodly.business.test").run(context ->
                assertThat(context.getBean(TopicExchange.class).getName()).isEqualTo("foodly.business.test"));
    }

    @Test
    void theRadarQueueIsBoundToExactlyTheThreeBusinessRoutingKeys() {
        runner.run(context -> {
            List<Binding> bindings = context.getBean(Declarables.class).getDeclarablesByType(Binding.class);

            assertThat(bindings).filteredOn(binding -> binding.getDestination().equals(RabbitMQConfig.QUEUE))
                    .extracting(Binding::getRoutingKey)
                    .containsExactlyInAnyOrder("business.HuariqueRegistrado", "business.PerfilDelHuariqueActualizado",
                            "business.EstadoAbiertoCerradoCambiado");
            assertThat(bindings).allSatisfy(binding -> assertThat(binding.getExchange()).isEqualTo("foodly.business"));
        });
    }

    @Test
    void theQueueIsDurableAndDeadLettersToTheDlq() {
        runner.run(context -> {
            Queue queue = context.getBean("businessEventsQueue", Queue.class);

            assertThat(queue.getName()).isEqualTo("radar.business-events");
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments())
                    .containsEntry("x-dead-letter-exchange", "foodly.dlx")
                    .containsEntry("x-dead-letter-routing-key", "radar.business-events.dlq");
        });
    }

    @Test
    void theDeadLetterExchangeAndQueueAreDeclaredAndBound() {
        runner.run(context -> {
            DirectExchange dlx = context.getBean(DirectExchange.class);
            Queue dlq = context.getBean("deadLetterQueue", Queue.class);
            Binding binding = context.getBean("deadLetterBinding", Binding.class);

            assertThat(dlx.getName()).isEqualTo("foodly.dlx");
            assertThat(dlx.isDurable()).isTrue();
            assertThat(dlq.getName()).isEqualTo("radar.business-events.dlq");
            assertThat(dlq.isDurable()).isTrue();
            assertThat(binding.getExchange()).isEqualTo("foodly.dlx");
            assertThat(binding.getDestination()).isEqualTo("radar.business-events.dlq");
            assertThat(binding.getRoutingKey()).isEqualTo("radar.business-events.dlq");
        });
    }
}
