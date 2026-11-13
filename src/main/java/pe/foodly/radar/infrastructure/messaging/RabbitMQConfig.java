package pe.foodly.radar.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topología de RabbitMQ (CloudAMQP) para los eventos de Business (5.2.4):
 * un exchange topic, la cola durable de Radar enlazada a los cuatro eventos, y una Dead Letter Queue.
 *
 * <p>La conexión se configura con {@code RABBITMQ_URL} (en {@code spring.rabbitmq.addresses}):
 * {@code amqp://usuario:contraseña@host:5672/vhost}, o {@code amqps://...} con TLS (CloudAMQP).
 * Con {@code amqps} Spring Boot activa TLS con el almacén de confianza de la JVM y verifica el nombre del host.
 */
@Configuration
@EnableConfigurationProperties(EventRetryProperties.class)
public class RabbitMQConfig {

    public static final String BUSINESS_EVENTS_EXCHANGE = "foodly.business.events";
    public static final String QUEUE = "radar.business-events";
    public static final String DEAD_LETTER_EXCHANGE = "foodly.dlx";
    public static final String DEAD_LETTER_QUEUE = "radar.business-events.dlq";

    public static final String HUARIQUE_REGISTERED_KEY = "huarique.registered";
    public static final String HUARIQUE_UPDATED_KEY = "huarique.updated";
    public static final String LOCATION_UPDATED_KEY = "huarique.location-updated";
    public static final String OPENING_STATUS_CHANGED_KEY = "huarique.opening-status-changed";

    @Bean
    public TopicExchange businessEventsExchange() {
        return ExchangeBuilder.topicExchange(BUSINESS_EVENTS_EXCHANGE).durable(true).build();
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
    public Declarables businessEventsBindings() {
        TopicExchange exchange = businessEventsExchange();
        Queue queue = businessEventsQueue();
        return new Declarables(
                BindingBuilder.bind(queue).to(exchange).with(HUARIQUE_REGISTERED_KEY),
                BindingBuilder.bind(queue).to(exchange).with(HUARIQUE_UPDATED_KEY),
                BindingBuilder.bind(queue).to(exchange).with(LOCATION_UPDATED_KEY),
                BindingBuilder.bind(queue).to(exchange).with(OPENING_STATUS_CHANGED_KEY));
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
