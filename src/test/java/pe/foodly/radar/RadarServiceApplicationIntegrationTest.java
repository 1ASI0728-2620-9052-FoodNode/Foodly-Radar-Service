package pe.foodly.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.events.DomainEvent;
import pe.foodly.radar.domain.model.events.H3CellCalculated;
import pe.foodly.radar.domain.model.events.NearbyHuariquesFound;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;
import pe.foodly.radar.domain.services.IH3IndexService;
import pe.foodly.radar.support.TestJwt;

/**
 * Levanta la aplicación completa (application.yml, beans, seguridad, OpenAPI) contra un Redis real y un H3 real.
 * Se omite si no hay Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class RadarServiceApplicationIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("radar.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        registry.add("radar.security.jwt.secret", () -> TestJwt.SECRET);
        // Esta prueba no usa RabbitMQ: el listener no arranca y no intenta conectarse.
        registry.add("spring.rabbitmq.addresses", () -> "amqp://guest:guest@localhost:5672");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
    }

    private static final String BODY = """
            { "latitude": -12.1219, "longitude": -77.0297, "kRing": 3 }""";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private IHuariqueLocationRepository repository;
    @Autowired
    private IH3IndexService h3IndexService;
    @Autowired
    private ApplicationEvents applicationEvents;

    @BeforeEach
    void flushRedis() {
        redis.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    private void index(String id, double lat, double lng, AvailabilityStatus status) {
        GeoCoordinates coordinates = new GeoCoordinates(lat, lng);
        repository.save(new HuariqueLocation(new HuariqueId(id), coordinates, h3IndexService.toCell(coordinates), status));
    }

    private ResultActions search(String body, String token) throws Exception {
        var request = post("/radar/search").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    @Test
    void searchReturnsOnlyActiveHuariquesOfTheZoneOrderedByDistance() throws Exception {
        index("mid", -12.1249, -77.0297, AvailabilityStatus.ACTIVE);       // ~334 m
        index("near", -12.1229, -77.0297, AvailabilityStatus.ACTIVE);      // ~111 m
        index("inactive", -12.1224, -77.0297, AvailabilityStatus.INACTIVE); // ~56 m, pero inactivo
        index("barranco", -12.1498, -77.0207, AvailabilityStatus.ACTIVE);  // ~3,2 km, fuera de la zona

        search(BODY, TestJwt.explorer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.huariques[0].huariqueId").value("near"))
                .andExpect(jsonPath("$.huariques[1].huariqueId").value("mid"))
                .andExpect(jsonPath("$.huariques[0].distanceMeters")
                        .value(org.hamcrest.Matchers.closeTo(111.2, 1.0)))
                .andExpect(jsonPath("$.huariques[1].distanceMeters")
                        .value(org.hamcrest.Matchers.closeTo(334.0, 2.0)));
    }

    @Test
    void searchPublishesTheDomainEventsOfTheSearch() throws Exception {
        index("near", -12.1229, -77.0297, AvailabilityStatus.ACTIVE);
        long before = applicationEvents.stream(DomainEvent.class).count();

        search(BODY, TestJwt.explorer()).andExpect(status().isOk());

        var published = applicationEvents.stream(DomainEvent.class).skip(before).toList();
        assertThat(published).hasSize(2);
        assertThat(published.get(0)).isInstanceOf(H3CellCalculated.class);
        assertThat(published.get(1)).isInstanceOf(NearbyHuariquesFound.class);
        assertThat(((NearbyHuariquesFound) published.get(1)).total()).isEqualTo(1);
    }

    @Test
    void searchInAnEmptyZoneReturns200WithAnEmptyList() throws Exception {
        search("{ \"latitude\": -13.5319, \"longitude\": -71.9675, \"kRing\": 2 }", TestJwt.explorer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.huariques", hasSize(0)))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void searchRejectsAKRingAboveTheDefaultMaximumOfFive() throws Exception {
        search("{ \"latitude\": -12.1219, \"longitude\": -77.0297, \"kRing\": 6 }", TestJwt.explorer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("No se pudo procesar la consulta del radar: el k-ring debe estar entre 0 y 5"));
    }

    @Test
    void searchRejectsOutOfRangeCoordinates() throws Exception {
        search("{ \"latitude\": 120, \"longitude\": -77.0297, \"kRing\": 1 }", TestJwt.explorer())
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchRequiresAValidTokenAndTheExplorerRole() throws Exception {
        search(BODY, null).andExpect(status().isUnauthorized());
        search(BODY, TestJwt.signedWith("another-secret-another-secret-another-0123456789"))
                .andExpect(status().isUnauthorized());
        search(BODY, TestJwt.owner()).andExpect(status().isForbidden());
    }

    @Test
    void statusIsPublicAndReportsUpWhileRedisIsUp() throws Exception {
        mockMvc.perform(get("/radar/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void healthSwaggerAndApiDocsArePublic() throws Exception {
        // El estado global puede ser DOWN (aún no hay broker), pero nunca 401 ni 403.
        int health = mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
        assertThat(health).isIn(200, 503);

        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        int swagger = mockMvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(swagger).isIn(200, 302);
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void apiDocsDescribeTheSearchEndpointWithTheBearerScheme() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/radar/search")))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.paths['/radar/search'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/radar/search'].post.summary").value("Buscar huariques cercanos"));
    }
}
