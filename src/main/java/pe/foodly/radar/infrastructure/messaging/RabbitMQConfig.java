package pe.foodly.radar.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de RabbitMQ (CloudAMQP) para los eventos de Business v1 (5.2.4): el exchange topic de Business, la cola
 * durable de Radar enlazada a los tres eventos que usa, y una Dead Letter Queue.
 *
 * <p>El exchange es el que publica Business ({@code foodly.business}, topic durable, configurable con
 * {@code BUSINESS_EVENTS_EXCHANGE}) y las routing keys son {@code business.<Tipo>}. Business solo declara el exchange:
 * la cola, sus bindings y la DLQ los declara Radar.
 *
 * <p>La conexión se configura con {@code RABBITMQ_URL} (en {@code spring.rabbitmq.addresses}):
 * {@code amqp://usuario:contraseña@host:5672/vhost}, o {@code amqps://...} con TLS (CloudAMQP).
 * Con {@code amqps} Spring Boot activa TLS con el almacén de confianza de la JVM y verifica el nombre del host.
 */
@Configuration
@EnableConfigurationProperties(EventRetryProperties.class)
public class RabbitMQConfig {

    public static final String DEFAULT_BUSINESS_EVENTS_EXCHANGE = "foodly.business";
    public static final String QUEUE = "radar.business-events";
    public static final String DEAD_LETTER_EXCHANGE = "foodly.dlx";
    public static final String DEAD_LETTER_QUEUE = "radar.business-events.dlq";

    /** Prefijo de las routing keys de Business: {@code business.<Tipo>}. */
    public static final String ROUTING_KEY_PREFIX = "business.";
    public static final String HUARIQUE_REGISTERED_KEY = BusinessEventType.HUARIQUE_REGISTERED.routingKey();
    public static final String HUARIQUE_UPDATED_KEY = BusinessEventType.HUARIQUE_UPDATED.routingKey();
    public static final String OPENING_STATUS_CHANGED_KEY = BusinessEventType.OPENING_STATUS_CHANGED.routingKey();

    @Bean
    public TopicExchange businessEventsExchange(
            @Value("${radar.events.exchange:" + DEFAULT_BUSINESS_EVENTS_EXCHANGE + "}") String exchangeName) {
        return ExchangeBuilder.topicExchange(exchangeName).durable(true).build();
    }

    /** Cola durable propia de Radar: si Radar está caído, los eventos esperan aquí (QA-3). */
    @Bean
    public Queue businessEventsQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    public Declarables businessEventsBindings(TopicExchange businessEventsExchange, Queue businessEventsQueue) {
        return new Declarables(
                BindingBuilder.bind(businessEventsQueue).to(businessEventsExchange).with(HUARIQUE_REGISTERED_KEY),
                BindingBuilder.bind(businessEventsQueue).to(businessEventsExchange).with(HUARIQUE_UPDATED_KEY),
                BindingBuilder.bind(businessEventsQueue).to(businessEventsExchange).with(OPENING_STATUS_CHANGED_KEY));
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DEAD_LETTER_QUEUE);
    }

    @Bean
    public Sleeper sleeper() {
        return Thread::sleep;
    }
}
