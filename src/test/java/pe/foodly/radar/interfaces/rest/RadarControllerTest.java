package pe.foodly.radar.interfaces.rest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import pe.foodly.radar.application.exceptions.InvalidRadarSearchException;
import pe.foodly.radar.application.queries.NearbyHuarique;
import pe.foodly.radar.application.queries.SearchNearbyHuariquesQuery;
import pe.foodly.radar.application.queryhandlers.SearchNearbyHuariquesQueryHandler;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.Distance;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.infrastructure.security.SecurityConfig;
import pe.foodly.radar.support.TestJwt;

@WebMvcTest(RadarController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "radar.security.jwt.secret=" + TestJwt.SECRET,
        "radar.security.jwt.user-id-claim=sub",
        "radar.security.jwt.role-claim=role"})
class RadarControllerTest {

    private static final String BODY = """
            { "latitude": -12.1219, "longitude": -77.0297, "kRing": 2 }""";
    private static final String PREFIX = "No se pudo procesar la consulta del radar: ";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchNearbyHuariquesQueryHandler handler;

    private ResultActions search(String body, String token) throws Exception {
        var request = post("/radar/search").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private NearbyHuarique nearby(String id, double lat, double lng, double meters) {
        return new NearbyHuarique(
                new HuariqueLocation(new HuariqueId(id), new GeoCoordinates(lat, lng), new H3Cell("cell"),
                        AvailabilityStatus.ACTIVE),
                new Distance(meters));
    }

    // ---- 200 ----

    @Test
    void returns200WithTheResultsInOrder() throws Exception {
        when(handler.handle(any())).thenReturn(List.of(
                nearby("h-near", -12.1229, -77.0297, 111.2),
                nearby("h-far", -12.1498, -77.0207, 3252.96)));

        search(BODY, TestJwt.explorer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.huariques", hasSize(2)))
                .andExpect(jsonPath("$.huariques[0].huariqueId").value("h-near"))
                .andExpect(jsonPath("$.huariques[0].latitude").value(-12.1229))
                .andExpect(jsonPath("$.huariques[0].longitude").value(-77.0297))
                .andExpect(jsonPath("$.huariques[0].distanceMeters").value(111.2))
                .andExpect(jsonPath("$.huariques[1].huariqueId").value("h-far"));

        verify(handler).handle(new SearchNearbyHuariquesQuery(-12.1219, -77.0297, 2));
    }

    @Test
    void returns200WithAnEmptyListAndTotalZeroWhenTheZoneHasNoHuariques() throws Exception {
        when(handler.handle(any())).thenReturn(List.of());

        search(BODY, TestJwt.explorer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.huariques", hasSize(0)))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void acceptsTheBoundariesOfTheCoordinateRanges() throws Exception {
        when(handler.handle(any())).thenReturn(List.of());

        search("{ \"latitude\": 90, \"longitude\": -180, \"kRing\": 0 }", TestJwt.explorer())
                .andExpect(status().isOk());
    }

    // ---- 400 ----

    @Test
    void returns400WhenTheLatitudeIsOutOfRange() throws Exception {
        search("{ \"latitude\": 91, \"longitude\": -77.0297, \"kRing\": 2 }", TestJwt.explorer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(PREFIX + "la latitud debe estar entre -90 y 90"))
                .andExpect(jsonPath("$.path").value("/radar/search"))
                .andExpect(jsonPath("$.timestamp").exists());

        verifyNoInteractions(handler);
    }

    @Test
    void returns400WhenTheLongitudeIsOutOfRange() throws Exception {
        search("{ \"latitude\": -12.1, \"longitude\": -180.5, \"kRing\": 2 }", TestJwt.explorer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PREFIX + "la longitud debe estar entre -180 y 180"));
    }

    @Test
    void returns400WhenTheKRingIsNegative() throws Exception {
        search("{ \"latitude\": -12.1, \"longitude\": -77.0, \"kRing\": -1 }", TestJwt.explorer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PREFIX + "el k-ring no puede ser negativo"));
    }

    @Test
    void returns400WhenAFieldIsMissing() throws Exception {
        search("{ \"latitude\": -12.1, \"longitude\": -77.0 }", TestJwt.explorer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PREFIX + "el k-ring es obligatorio"));
    }

    @Test
    void reportsEveryInvalidFieldInTheMessage() throws Exception {
        search("{ \"latitude\": 91, \"longitude\": -77.0, \"kRing\": -1 }", TestJwt.explorer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith(PREFIX)))
                .andExpect(jsonPath("$.message", containsString("la latitud debe estar entre -90 y 90")))
                .andExpect(jsonPath("$.message", containsString("el k-ring no puede ser negativo")));
    }

    @Test
    void returns400WhenTheKRingExceedsTheConfiguredMaximum() throws Exception {
        when(handler.handle(any())).thenThrow(new InvalidRadarSearchException("el k-ring debe estar entre 0 y 5"));

        search("{ \"latitude\": -12.1, \"longitude\": -77.0, \"kRing\": 9 }", TestJwt.explorer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PREFIX + "el k-ring debe estar entre 0 y 5"));
    }

    @Test
    void returns400ForAMalformedBodyWithoutLeakingParserDetails() throws Exception {
        search("{ \"latitude\": ", TestJwt.explorer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith(PREFIX)))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(containsString("Jackson"))));
    }

    @Test
    void returns400WhenAFieldHasTheWrongType() throws Exception {
        search("{ \"latitude\": \"abc\", \"longitude\": -77.0, \"kRing\": 2 }", TestJwt.explorer())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", startsWith(PREFIX)));
    }

    // ---- 401 ----

    @Test
    void returns401WithoutToken() throws Exception {
        search(BODY, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/radar/search"))
                .andExpect(jsonPath("$.timestamp").exists());

        verifyNoInteractions(handler);
    }

    @Test
    void returns401WithAnInvalidSignature() throws Exception {
        search(BODY, TestJwt.signedWith("another-secret-another-secret-another-0123456789"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns401WithAnExpiredToken() throws Exception {
        search(BODY, TestJwt.expired()).andExpect(status().isUnauthorized());
    }

    @Test
    void returns401BeforeValidatingTheBodyWhenThereIsNoToken() throws Exception {
        search("{ \"latitude\": 999 }", null).andExpect(status().isUnauthorized());
    }

    // ---- 403 ----

    @Test
    void returns403ForAHuariqueOwnerToken() throws Exception {
        search(BODY, TestJwt.owner())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.path").value("/radar/search"));

        verifyNoInteractions(handler);
    }

    // ---- otros errores con el formato uniforme ----

    @Test
    void unsupportedMethodUsesTheUniformErrorFormat() throws Exception {
        mockMvc.perform(get("/radar/search").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.explorer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("El método HTTP no está permitido para este recurso."));
    }

    @Test
    void unsupportedContentTypeUsesTheUniformErrorFormat() throws Exception {
        mockMvc.perform(post("/radar/search").contentType(MediaType.TEXT_PLAIN).content("hola")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.explorer()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void anUnexpectedFailureReturns500WithoutLeakingDetails() throws Exception {
        when(handler.handle(any())).thenThrow(new IllegalStateException("redis://secret@internal:6379 caído"));

        search(BODY, TestJwt.explorer())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(containsString("redis"))));
    }
}
