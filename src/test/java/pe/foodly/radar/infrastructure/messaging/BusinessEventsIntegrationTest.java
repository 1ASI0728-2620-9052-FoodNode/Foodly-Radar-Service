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
 * Radar completo contra un RabbitMQ y un Redis reales, con eventos en el formato de Business v1
 * ({@code foodly.business}, routing keys {@code business.<Tipo>}, sobre con {@code aggregateVersion} y
 * {@code data}). Se omite si no hay Docker.
 *
 * <p>Los eventos se procesan de uno en uno y en orden (prefetch 1, un solo consumidor). Por eso, para saber que un
 * mensaje ya se procesó se publica después un huarique «centinela» y se espera a que aparezca en Redis.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class BusinessEventsIntegrationTest {

    private static final String EXCHANGE = RabbitMQConfig.DEFAULT_BUSINESS_EVENTS_EXCHANGE;

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
        registry.add("radar.security.jwt.secret-base64", () -> TestJwt.SECRET_BASE64);
        // Espera corta entre reintentos para que la prueba de fallos no tarde.
        registry.add("radar.events.initial-backoff-ms", () -> "20");
    }

    /** Snapshot de Business (data): lleva más campos de los que Radar usa. */
    private static String snapshot(String openStatus, double latitude, double longitude) {
        return """
                { "id": "h", "ownerId": "owner-1", "name": "El Huarique", "address": "Lima 123",
                  "cuisineType": "CRIOLLA", "priceRange": { "minimum": 10, "maximum": 50 },
                  "location": { "latitude": %s, "longitude": %s }, "openStatus": "%s",
                  "tags": [], "openingHours": [], "images": [], "version": 0 }""".formatted(latitude, longitude, openStatus);
    }

    private static final String MIRAFLORES = snapshot("OPEN", -12.1219, -77.0297);
    private static final String BARRANCO = snapshot("OPEN", -12.1498, -77.0207);

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

    private static String envelope(String eventId, String type, String huariqueId, long aggregateVersion, String data) {
        return """
                { "eventId": "%s", "type": "%s", "schemaVersion": 1, "huariqueId": "%s", "aggregateVersion": %d,
                  "occurredAt": "2026-11-24T20:00:00.123Z", "data": %s }""".formatted(eventId, type, huariqueId,
                aggregateVersion, data);
    }

    private void publish(String routingKey, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .andProperties(properties).build();
        rabbitTemplate.send(EXCHANGE, routingKey, message);
    }

    private void publishRegistered(String eventId, String huariqueId, long aggregateVersion, String data) {
        publish(RabbitMQConfig.HUARIQUE_REGISTERED_KEY,
                envelope(eventId, "HuariqueRegistrado", huariqueId, aggregateVersion, data));
    }

    private void publishUpdated(String eventId, String huariqueId, long aggregateVersion, String data) {
        publish(RabbitMQConfig.HUARIQUE_UPDATED_KEY,
                envelope(eventId, "PerfilDelHuariqueActualizado", huariqueId, aggregateVersion, data));
    }

    private void publishStatusChanged(String eventId, String huariqueId, long aggregateVersion, String data) {
        publish(RabbitMQConfig.OPENING_STATUS_CHANGED_KEY,
                envelope(eventId, "EstadoAbiertoCerradoCambiado", huariqueId, aggregateVersion, data));
    }

    /** Publica un huarique centinela y espera a que se indexe: todo lo publicado antes ya fue procesado. */
    private void awaitEverythingPublishedSoFarIsProcessed() {
        String sentinel = "sentinel-" + sentinelCounter.incrementAndGet();
        publishRegistered("e-" + sentinel, sentinel, 0, MIRAFLORES);
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

    // ---- aggregateVersion empieza en 0 ----

    @Test
    void theFirstEventWithAggregateVersionZeroIsApplied() {
        publishRegistered("e-0", "first-1", 0, MIRAFLORES);
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(hash("first-1")).containsEntry("version", "0").containsEntry("status", "ACTIVE")
                .containsEntry("lat", "-12.1219");
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)).isNull();
    }

    @Test
    void theSameVersionZeroEventPublishedAgainIsDiscarded() {
        publishRegistered("e-0", "first-2", 0, MIRAFLORES);
        awaitEverythingPublishedSoFarIsProcessed();
        Map<Object, Object> afterFirst = hash("first-2");

        publishRegistered("e-0", "first-2", 0, snapshot("CLOSED", -12.1498, -77.0207));
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(hash("first-2")).isEqualTo(afterFirst);
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

        publishUpdated("e-3", "h-1", 3, snapshot("CLOSED", -12.1498, -77.0207));
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(hash("h-1")).isEqualTo(current);
        assertThat(hash("h-1")).containsEntry("lat", "-12.1219").containsEntry("status", "ACTIVE")
                .containsEntry("version", "5");
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)).isNull();
    }

    @Test
    void aNewerVersionIsApplied() {
        publishRegistered("e-1", "h-1", 0, MIRAFLORES);
        publishUpdated("e-2", "h-1", 1, snapshot("CLOSED", -12.1219, -77.0297));
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(hash("h-1")).containsEntry("status", "INACTIVE").containsEntry("version", "1");
    }

    // ---- cambio de ubicación (llega dentro de PerfilDelHuariqueActualizado) ----

    @Test
    void aProfileUpdateWithANewLocationMovesTheHuariqueToTheNewCell() {
        H3Cell miraflores = cellOf(-12.1219, -77.0297);
        H3Cell barranco = cellOf(-12.1498, -77.0207);
        assertThat(miraflores).isNotEqualTo(barranco);

        publishRegistered("e-1", "h-1", 0, MIRAFLORES);
        awaitEverythingPublishedSoFarIsProcessed();
        assertThat(members(miraflores)).contains("h-1");

        publishUpdated("e-2", "h-1", 1, BARRANCO);
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(hash("h-1")).containsEntry("h3Cell", barranco.index()).containsEntry("lat", "-12.1498")
                .containsEntry("version", "1");
        assertThat(members(barranco)).contains("h-1");
        assertThat(members(miraflores)).doesNotContain("h-1");
    }

    @Test
    void openAndClosedFlipTheAvailability() {
        publishRegistered("e-1", "h-1", 0, snapshot("CLOSED", -12.1219, -77.0297));
        awaitEverythingPublishedSoFarIsProcessed();
        assertThat(hash("h-1")).containsEntry("status", "INACTIVE");

        publishStatusChanged("e-2", "h-1", 1, snapshot("OPEN", -12.1219, -77.0297));
        awaitEverythingPublishedSoFarIsProcessed();
        assertThat(hash("h-1")).containsEntry("status", "ACTIVE").containsEntry("version", "1");

        publishStatusChanged("e-3", "h-1", 2, snapshot("CLOSED", -12.1219, -77.0297));
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
        publishRegistered("e-1", "h-1", 1, "{ \"openStatus\": \"OPEN\" }");

        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE, 15_000)).isNotNull();
        assertThat(redis.hasKey(huariqueKey(new HuariqueId("h-1")))).isFalse();
    }

    @Test
    void anEventThatKeepsFailingIsRetriedAndThenGoesToTheDeadLetterQueue() {
        // El huarique no está indexado: ChangeHuariqueAvailability falla en cada intento.
        publishStatusChanged("e-1", "ghost", 1, "{ \"openStatus\": \"CLOSED\" }");

        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE, 15_000)).isNotNull();
        assertThat(redis.hasKey(huariqueKey(new HuariqueId("ghost")))).isFalse();
    }

    // ---- tipos que Radar no procesa ----

    @Test
    void menuEventsAreNotRoutedToTheRadarQueue() {
        // MenuCreado y MenuActualizado no están enlazados a la cola de Radar: ni llegan ni dejan huella.
        publish("business.MenuCreado", envelope("e-1", "MenuCreado", "h-1", 0, "{ \"categories\": [] }"));
        publish("business.MenuActualizado", envelope("e-2", "MenuActualizado", "h-1", 1, "{ \"categories\": [] }"));
        awaitEverythingPublishedSoFarIsProcessed();

        assertThat(redis.hasKey(huariqueKey(new HuariqueId("h-1")))).isFalse();
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)).isNull();
        assertThat(queueDepth()).isZero();
    }

    @Test
    void aMenuEventThatReachesTheQueueByAnotherBindingIsAckedAndIgnored() {
        publish(RabbitMQConfig.HUARIQUE_UPDATED_KEY, envelope("e-1", "MenuActualizado", "h-1", 1, "{ \"categories\": [] }"));
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
    void eachOfTheThreeRoutingKeysReachesTheRadarQueue() {
        publishRegistered("e-1", "k-1", 0, MIRAFLORES);
        publishUpdated("e-2", "k-1", 1, BARRANCO);
        publishStatusChanged("e-3", "k-1", 2, snapshot("CLOSED", -12.1498, -77.0207));
        awaitEverythingPublishedSoFarIsProcessed();

        // Versión 2 = el último de los tres eventos se aplicó, así que ninguno se perdió ni se desvió.
        assertThat(hash("k-1")).containsEntry("version", "2").containsEntry("status", "INACTIVE")
                .containsEntry("lat", "-12.1498");
        assertThat(rabbitTemplate.receive(RabbitMQConfig.DEAD_LETTER_QUEUE)).isNull();
    }
}
