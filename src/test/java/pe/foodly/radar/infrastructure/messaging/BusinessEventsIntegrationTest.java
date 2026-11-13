package pe.foodly.radar.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static pe.foodly.radar.infrastructure.persistence.redis.RedisHuariqueLocationRepository.cellKey;
import static pe.foodly.radar.infrastructure.persistence.redis.RedisHuariqueLocationRepository.huariqueKey;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.services.IH3IndexService;
import pe.foodly.radar.support.TestJwt;

/**
 * Radar completo contra un RabbitMQ y un Redis reales. Se omite si no hay Docker.
 *
 * <p>Los eventos se procesan de uno en uno y en orden (prefetch 1, un solo consumidor). Por eso, para saber que un
 * mensaje ya se procesó se publica después un huarique «centinela» y se espera a que aparezca en Redis.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class BusinessEventsIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("radar.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.addresses",
                () -> "amqp://guest:guest@" + RABBIT.getHost() + ":" + RABBIT.getAmqpPort());
        registry.add("radar.security.jwt.secret", () -> TestJwt.SECRET);
        // Espera corta entre reintentos para que la prueba de fallos no tarde.
        registry.add("radar.events.initial-backoff-ms", () -> "20");
    }

    private static final String MIRAFLORES = """
            { "latitude": -12.1219, "longitude": -77.0297, "status": "ACTIVE" }""";
    private static final String BARRANCO = """
            { "latitude": -12.1498, "longitude": -77.0207, "status": "ACTIVE" }""";

    private final AtomicInteger sentinelCounter = new AtomicInteger();

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private IH3IndexService h3IndexService;

    @BeforeEach
    void cleanState() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        rabbitAdmin.purgeQueue(RabbitMQConfig.DEAD_LETTER_QUEUE, true);
    }

    // ---- utilidades ----

    private static String envelope(String eventId, String eventType, String huariqueId, long version, String payload) {
        return """
                { "eventId": "%s", "eventType": "%s", "huariqueId": "%s", "version": %d,
                  "occurredAt": "2026-11-13T12:00:00Z", "payload": %s }""".formatted(eventId, eventType, huariqueId,
                version, payload);
    }

    private void publish(String routingKey, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .andProperties(properties).build();
        rabbitTemplate.send(RabbitMQConfig.BUSINESS_EVENTS_EXCHANGE, routingKey, message);
    }

    private void publishRegistered(String eventId, String huariqueId, long version, String payload) {
        publish(RabbitMQConfig.HUARIQUE_REGISTERED_KEY, envelope(eventId, "HuariqueRegistered", huariqueId, version, payload));
    }

    /** Publica un huarique centinela y espera a que se indexe: todo lo publicado antes ya fue procesado. */
    private void awaitEverythingPublishedSoFarIsProcessed() {
        String sentinel = "sentinel-" + sentinelCounter.incrementAndGet();
        publishRegistered("e-" + sentinel, sentinel, 1, MIRAFLORES);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(
                () -> assertThat(redis.hasKey(huariqueKey(new HuariqueId(sentinel)))).isTrue());
    }

    private Map<Object, Object> hash(String id) {
        return redis.opsForHash().entries(huariqueKey(new HuariqueId(id)));
    }

    private Set<String> members(H3Cell cell) {
        Set<String> members = redis.opsForSet().members(cellKey(cell));
        return members == null ? Set.of() : members;
    }

    /** Los centinelas comparten celda con los huariques de las pruebas: se excluyen al comparar. */
    private Set<String> realMembers(H3Cell cell) {
        return members(cell).stream().filter(id -> !id.startsWith("sentinel-"))
                .collect(java.util.stream.Collectors.toSet());
    }

    private H3Cell cellOf(double latitude, double longitude) {
        return h3IndexService.toCell(new GeoCoordinates(latitude, longitude));
    }

    private int queueDepth() {
        var info = rabbitAdmin.getQueueInfo(RabbitMQConfig.QUEUE);
        return info == null ? -1 : info.getMessageCount();
    }

    // ---- idempotencia ----

    @Test
    void publishingTheSameEventTwiceLeavesRedisUnchanged() {
        publishRegistered("e-1", "dup-1", 1, MIRAFLORES);
        awaitEverythingPublishedSoFarIsProcessed();
        Map<Object, Object> afterFirst = hash("dup-1");
        assertThat(afterFirst).containsEntry("version", "1").containsEntry("status", "ACTIVE");
        assertThat(realMembers(cellOf(-12.1219, -77.0297))).containsExactly("dup-1");

        publishRegistered("e-1", "dup-1", 1, MIRAFLORES);
        awaitEverythingPublishedSoFarIsProcessed();

        // Incluye updatedAt: si el segundo evento se hubiera aplicado, la marca de tiempo habría cambiado.
        assertThat(hash("dup-1")).isEqualTo(afterFirst);
        assertThat(realMembers(cellOf(-12.1219, -77.0297))).containsExactly("dup-1");
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)).isNull();
        assertThat(queueDepth()).isZero();
    }

    // ---- versiones ----

    @Test
    void anOlderVersionDoesNotOverwriteANewerOne() {
        publishRegistered("e-5", "h-1", 5, MIRAFLORES);
        awaitEverythingPublishedSoFarIsProcessed();
        Map<Object, Object> current = hash("h-1");

        publish(RabbitMQConfig.HUARIQUE_UPDATED_KEY, envelope("e-3", "HuariqueUpdated", "h-1", 3,
                "{ \"latitude\": -12.1498, \"longitude\": -77.0207, \"status\": \"INACTIVE\" }"));
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(hash("h-1")).isEqualTo(current);
        assertThat(hash("h-1")).containsEntry("lat", "-12.1219").containsEntry("status", "ACTIVE")
                .containsEntry("version", "5");
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)).isNull();
    }

    @Test
    void aNewerVersionIsApplied() {
        publishRegistered("e-1", "h-1", 1, MIRAFLORES);
        publish(RabbitMQConfig.HUARIQUE_UPDATED_KEY, envelope("e-2", "HuariqueUpdated", "h-1", 2,
                "{ \"latitude\": -12.1219, \"longitude\": -77.0297, \"status\": \"INACTIVE\" }"));
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(hash("h-1")).containsEntry("status", "INACTIVE").containsEntry("version", "2");
    }

    // ---- cambio de ubicación ----

    @Test
    void locationUpdatedMovesTheHuariqueToTheNewCell() {
        H3Cell miraflores = cellOf(-12.1219, -77.0297);
        H3Cell barranco = cellOf(-12.1498, -77.0207);
        assertThat(miraflores).isNotEqualTo(barranco);

        publishRegistered("e-1", "h-1", 1, MIRAFLORES);
        awaitEverythingPublishedSoFarIsProcessed();
        assertThat(members(miraflores)).contains("h-1");

        publish(RabbitMQConfig.LOCATION_UPDATED_KEY, envelope("e-2", "LocationUpdated", "h-1", 2, BARRANCO));
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(hash("h-1")).containsEntry("h3Cell", barranco.index()).containsEntry("lat", "-12.1498")
                .containsEntry("version", "2");
        assertThat(members(barranco)).contains("h-1");
        assertThat(members(miraflores)).doesNotContain("h-1");
    }

    @Test
    void openingStatusChangedFlipsTheAvailability() {
        publishRegistered("e-1", "h-1", 1, MIRAFLORES);
        publish(RabbitMQConfig.OPENING_STATUS_CHANGED_KEY,
                envelope("e-2", "OpeningStatusChanged", "h-1", 2, "{ \"isOpen\": false }"));
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(hash("h-1")).containsEntry("status", "INACTIVE").containsEntry("version", "2")
                .containsEntry("lat", "-12.1219");
    }

    // ---- DLQ ----

    @Test
    void aCorruptPayloadEndsUpInTheDeadLetterQueue() {
        String garbage = "{{{ esto no es json";
        publish(RabbitMQConfig.HUARIQUE_REGISTERED_KEY, garbage);

        Message dead = rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE, 15_000);

        assertThat(dead).as("el mensaje corrupto debe llegar a la DLQ").isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo(garbage);
        awaitEverythingPublishedSoFarIsProcessed();
        assertThat(queueDepth()).isZero();
        // Solo existe el centinela: el mensaje corrupto no dejó nada en Redis.
        assertThat(redis.keys(huariqueKey(new HuariqueId("*")))).hasSize(sentinelCounter.get());
    }

    @Test
    void aValidJsonWithMissingFieldsAlsoEndsUpInTheDeadLetterQueue() {
        publish(RabbitMQConfig.HUARIQUE_REGISTERED_KEY,
                envelope("e-1", "HuariqueRegistered", "h-1", 1, "{ \"status\": \"ACTIVE\" }"));

        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE, 15_000)).isNotNull();
        assertThat(redis.hasKey(huariqueKey(new HuariqueId("h-1")))).isFalse();
    }

    @Test
    void anEventThatKeepsFailingIsRetriedAndThenGoesToTheDeadLetterQueue() {
        // El huarique no está indexado: ChangeHuariqueAvailability falla en cada intento.
        publish(RabbitMQConfig.OPENING_STATUS_CHANGED_KEY,
                envelope("e-1", "OpeningStatusChanged", "ghost", 1, "{ \"status\": \"INACTIVE\" }"));

        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE, 15_000)).isNotNull();
        assertThat(redis.hasKey(huariqueKey(new HuariqueId("ghost")))).isFalse();
    }

    // ---- tipos que Radar no procesa ----

    @Test
    void menuUpdatedIsAckedAndIgnored() {
        // MenuUpdated no está enlazado a la cola de Radar; si llegara por otro binding, se confirma y se ignora.
        publish(RabbitMQConfig.HUARIQUE_UPDATED_KEY, envelope("e-1", "MenuUpdated", "h-1", 1, "{ \"platos\": [] }"));
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(redis.hasKey(huariqueKey(new HuariqueId("h-1")))).isFalse();
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)).isNull();
        assertThat(queueDepth()).isZero();
    }

    // ---- topología ----

    @Test
    void theQueuesAreDeclaredAndOnlyTheRadarQueueHasAConsumer() {
        var queue = rabbitAdmin.getQueueProperties(RabbitMQConfig.QUEUE);
        var dlq = rabbitAdmin.getQueueProperties(RabbitMQConfig.DEAD_LETTER_QUEUE);

        assertThat(queue).as("cola de Radar").isNotNull();
        assertThat(queue.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)).isEqualTo(1);
        assertThat(dlq).as("DLQ").isNotNull();
        assertThat(dlq.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)).isEqualTo(0);
    }

    @Test
    void eachOfTheFourRoutingKeysReachesTheRadarQueue() {
        publish(RabbitMQConfig.HUARIQUE_REGISTERED_KEY, envelope("e-1", "HuariqueRegistered", "k-1", 1, MIRAFLORES));
        publish(RabbitMQConfig.HUARIQUE_UPDATED_KEY, envelope("e-2", "HuariqueUpdated", "k-1", 2, MIRAFLORES));
        publish(RabbitMQConfig.LOCATION_UPDATED_KEY, envelope("e-3", "LocationUpdated", "k-1", 3, BARRANCO));
        publish(RabbitMQConfig.OPENING_STATUS_CHANGED_KEY,
                envelope("e-4", "OpeningStatusChanged", "k-1", 4, "{ \"status\": \"INACTIVE\" }"));
        awaitEverythingPublishedSoFarIsProcessed();

        // Versión 4 = el último de los cuatro eventos se aplicó, así que ninguno se perdió ni se desvió.
        assertThat(hash("k-1")).containsEntry("version", "4").containsEntry("status", "INACTIVE")
                .containsEntry("lat", "-12.1498");
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)).isNull();
    }
}
