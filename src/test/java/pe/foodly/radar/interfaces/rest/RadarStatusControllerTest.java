package pe.foodly.radar.interfaces.rest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pe.foodly.radar.application.queries.RadarServiceStatus;
import pe.foodly.radar.application.queryhandlers.GetRadarStatusQueryHandler;
import pe.foodly.radar.infrastructure.security.SecurityConfig;
import pe.foodly.radar.support.TestJwt;

@WebMvcTest(RadarStatusController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "radar.security.jwt.secret=" + TestJwt.SECRET,
        "radar.security.jwt.user-id-claim=sub",
        "radar.security.jwt.role-claim=role"})
class RadarStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetRadarStatusQueryHandler handler;

    @Test
    void returns200UpWithoutTokenBecauseTheEndpointIsPublic() throws Exception {
        when(handler.handle()).thenReturn(RadarServiceStatus.UP);

        mockMvc.perform(get("/radar/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void returns503DownWhenTheServiceCannotServeSearches() throws Exception {
        when(handler.handle()).thenReturn(RadarServiceStatus.DOWN);

        mockMvc.perform(get("/radar/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }

    @Test
    void anInvalidTokenDoesNotBreakThePublicEndpoint() throws Exception {
        when(handler.handle()).thenReturn(RadarServiceStatus.UP);

        mockMvc.perform(get("/radar/status").header(HttpHeaders.AUTHORIZATION, "Bearer basura"))
                .andExpect(status().isOk());
    }

    @Test
    void anyOtherPathStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/radar/otra-cosa"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void anUnknownPathWithAValidTokenReturnsTheUniform404() throws Exception {
        mockMvc.perform(get("/radar/otra-cosa").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.explorer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/radar/otra-cosa"));
    }
}
