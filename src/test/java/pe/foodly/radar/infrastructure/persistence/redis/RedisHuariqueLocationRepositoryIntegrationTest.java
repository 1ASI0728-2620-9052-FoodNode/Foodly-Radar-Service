package pe.foodly.radar.infrastructure.persistence.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pe.foodly.radar.infrastructure.persistence.redis.RedisHuariqueLocationRepository.cellKey;
import static pe.foodly.radar.infrastructure.persistence.redis.RedisHuariqueLocationRepository.huariqueKey;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.infrastructure.config.RedisConfig;

/** Se omite si no hay Docker. Usa un Redis real y la misma {@link RedisConfig} que la aplicación. */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(RedisConfig.class)
class RedisHuariqueLocationRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("radar.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("radar.redis.command-timeout-ms", () -> "5000");
    }

    private static final H3Cell CELL_A = new H3Cell("89a-cell-a");
    private static final H3Cell CELL_B = new H3Cell("89b-cell-b");
    private static final H3Cell CELL_C = new H3Cell("89c-cell-c");

    @Autowired
    private StringRedisTemplate redis;

    private RedisHuariqueLocationRepository repository;

    @BeforeEach
    void setUp() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        repository = new RedisHuariqueLocationRepository(redis, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private HuariqueLocation location(String id, double lat, double lng, H3Cell cell, AvailabilityStatus status) {
        return new HuariqueLocation(new HuariqueId(id), new GeoCoordinates(lat, lng), cell, status);
    }

    private Set<String> members(H3Cell cell) {
        Set<String> members = redis.opsForSet().members(cellKey(cell));
        return members == null ? Set.of() : members;
    }

    private Map<Object, Object> hash(String id) {
        return redis.opsForHash().entries(huariqueKey(new HuariqueId(id)));
    }

    // ---- upsert ----

    @Test
    void upsertStoresTheHashAndTheCellSet() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));

        assertThat(hash("h-1")).containsOnly(
                Map.entry("lat", "-12.1219"),
                Map.entry("lng", "-77.0297"),
                Map.entry("h3Cell", CELL_A.index()),
                Map.entry("status", "ACTIVE"),
                Map.entry("updatedAt", String.valueOf(NOW.toEpochMilli())));
        assertThat(members(CELL_A)).containsExactly("h-1");
    }

    @Test
    void upsertIsIdempotent() {
        HuariqueLocation location = location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE);

        repository.save(location);
        Map<Object, Object> afterFirst = hash("h-1");
        repository.save(location);

        assertThat(hash("h-1")).isEqualTo(afterFirst);
        assertThat(members(CELL_A)).containsExactly("h-1");
    }

    @Test
    void upsertReplacesTheDataOfAnExistingHuarique() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));
        repository.save(location("h-1", -12.1220, -77.0298, CELL_A, AvailabilityStatus.INACTIVE));

        HuariqueLocation stored = repository.findById(new HuariqueId("h-1")).orElseThrow();
        assertThat(stored.getCoordinates()).isEqualTo(new GeoCoordinates(-12.1220, -77.0298));
        assertThat(stored.getStatus()).isEqualTo(AvailabilityStatus.INACTIVE);
        assertThat(members(CELL_A)).containsExactly("h-1");
    }

    @Test
    void findByIdRebuildsTheAggregate() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));

        HuariqueLocation stored = repository.findById(new HuariqueId("h-1")).orElseThrow();

        assertThat(stored.getHuariqueId()).isEqualTo(new HuariqueId("h-1"));
        assertThat(stored.getCoordinates()).isEqualTo(new GeoCoordinates(-12.1219, -77.0297));
        assertThat(stored.getH3Cell()).isEqualTo(CELL_A);
        assertThat(stored.isActive()).isTrue();
        assertThat(repository.findById(new HuariqueId("missing"))).isEmpty();
    }

    // ---- mover de celda ----

    @Test
    void movingToAnotherCellRemovesTheIdFromTheOldSet() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));
        repository.save(location("h-2", -12.1220, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));

        repository.save(location("h-1", -12.1498, -77.0207, CELL_B, AvailabilityStatus.ACTIVE));

        assertThat(members(CELL_A)).containsExactly("h-2");
        assertThat(members(CELL_B)).containsExactly("h-1");
        assertThat(hash("h-1")).containsEntry("h3Cell", CELL_B.index());
    }

    @Test
    void movingTheLastHuariqueOutOfACellLeavesNoSetBehind() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));
        repository.save(location("h-1", -12.1498, -77.0207, CELL_B, AvailabilityStatus.ACTIVE));

        assertThat(members(CELL_A)).isEmpty();
        assertThat(redis.hasKey(cellKey(CELL_A))).isFalse();
    }

    // ---- version ----

    @Test
    void versionIsAbsentUntilItIsWritten() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));

        assertThat(repository.findVersion(new HuariqueId("h-1"))).isEmpty();
        assertThat(repository.findVersion(new HuariqueId("missing"))).isEmpty();
    }

    @Test
    void saveWithVersionWritesItAndPlainSaveKeepsIt() {
        HuariqueLocation location = location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE);

        repository.saveWithVersion(location, 5);
        assertThat(repository.findVersion(new HuariqueId("h-1"))).contains(5L);

        location.changeStatus(AvailabilityStatus.INACTIVE);
        repository.save(location);

        assertThat(repository.findVersion(new HuariqueId("h-1"))).contains(5L);
        assertThat(hash("h-1")).containsEntry("status", "INACTIVE");
    }

    @Test
    void saveWithVersionOverwritesThePreviousVersion() {
        HuariqueLocation location = location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE);

        repository.saveWithVersion(location, 5);
        repository.saveWithVersion(location, 9);

        assertThat(repository.findVersion(new HuariqueId("h-1"))).contains(9L);
    }

    @Test
    void writeVersionOnlyTouchesTheVersionField() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));
        Map<Object, Object> before = hash("h-1");

        repository.writeVersion(new HuariqueId("h-1"), 12);

        Map<Object, Object> after = hash("h-1");
        assertThat(after).containsEntry("version", "12");
        assertThat(after).containsAllEntriesOf(before);
        assertThat(repository.findVersion(new HuariqueId("h-1"))).contains(12L);
    }

    @Test
    void writeVersionDoesNotCreateAHashForAnUnknownHuarique() {
        repository.writeVersion(new HuariqueId("ghost"), 3);

        assertThat(redis.hasKey(huariqueKey(new HuariqueId("ghost")))).isFalse();
        assertThat(repository.findVersion(new HuariqueId("ghost"))).isEmpty();
    }

    @Test
    void negativeVersionIsRejectedByWriteVersionToo() {
        assertThatThrownBy(() -> repository.writeVersion(new HuariqueId("h-1"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeVersionIsRejected() {
        HuariqueLocation location = location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE);

        assertThatThrownBy(() -> repository.saveWithVersion(location, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- findByCells ----

    @Test
    void findByCellsReturnsTheHuariquesOfAllTheRequestedCells() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));
        repository.save(location("h-2", -12.1220, -77.0297, CELL_A, AvailabilityStatus.INACTIVE));
        repository.save(location("h-3", -12.1498, -77.0207, CELL_B, AvailabilityStatus.ACTIVE));
        repository.save(location("h-4", -12.2000, -77.0000, CELL_C, AvailabilityStatus.ACTIVE));

        List<HuariqueLocation> found = repository.findByCells(Set.of(CELL_A, CELL_B));

        // El repositorio no filtra por estado: eso lo hace RadarSearchService.
        assertThat(found).extracting(l -> l.getHuariqueId().value()).containsExactlyInAnyOrder("h-1", "h-2", "h-3");
        assertThat(found).filteredOn(l -> l.getHuariqueId().value().equals("h-2"))
                .singleElement().satisfies(l -> assertThat(l.isActive()).isFalse());
    }

    @Test
    void findByCellsWithNoMatchesOrNoCellsReturnsAnEmptyList() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));

        assertThat(repository.findByCells(Set.of(CELL_B))).isEmpty();
        assertThat(repository.findByCells(Set.of())).isEmpty();
    }

    @Test
    void findByCellsSkipsIdsWhoseHashNoLongerExists() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));
        redis.opsForSet().add(cellKey(CELL_A), "ghost");

        List<HuariqueLocation> found = repository.findByCells(Set.of(CELL_A));

        assertThat(found).extracting(l -> l.getHuariqueId().value()).containsExactly("h-1");
    }

    @Test
    void findByCellsSkipsCorruptHashesWithoutFailingTheWholeSearch() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));
        redis.opsForSet().add(cellKey(CELL_A), "broken");
        redis.opsForHash().put(huariqueKey(new HuariqueId("broken")), "lat", "not-a-number");

        List<HuariqueLocation> found = repository.findByCells(Set.of(CELL_A));

        assertThat(found).extracting(l -> l.getHuariqueId().value()).containsExactly("h-1");
    }

    @Test
    void findByCellsReturnsAHuariqueOnceEvenIfItIsInTwoRequestedSets() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));
        redis.opsForSet().add(cellKey(CELL_B), "h-1");

        assertThat(repository.findByCells(Set.of(CELL_A, CELL_B))).hasSize(1);
    }

    // ---- remove ----

    @Test
    void removeDeletesTheHashAndTheIdFromItsSet() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));
        repository.save(location("h-2", -12.1220, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));

        repository.remove(new HuariqueId("h-1"));

        assertThat(redis.hasKey(huariqueKey(new HuariqueId("h-1")))).isFalse();
        assertThat(members(CELL_A)).containsExactly("h-2");
        assertThat(repository.findById(new HuariqueId("h-1"))).isEmpty();
        assertThat(repository.findById(new HuariqueId("h-2"))).isPresent();
    }

    @Test
    void removingAnUnknownHuariqueDoesNothing() {
        repository.save(location("h-1", -12.1219, -77.0297, CELL_A, AvailabilityStatus.ACTIVE));

        repository.remove(new HuariqueId("missing"));

        assertThat(members(CELL_A)).containsExactly("h-1");
    }
}
