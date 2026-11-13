package pe.foodly.radar.application.queryhandlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import pe.foodly.radar.application.queries.RadarServiceStatus;

@ExtendWith(MockitoExtension.class)
class GetRadarStatusQueryHandlerTest {

    @Mock
    private HealthEndpoint healthEndpoint;

    @Test
    void isUpWhenRedisIsUp() {
        when(healthEndpoint.healthForPath("redis")).thenReturn(Health.up().build());

        assertThat(new GetRadarStatusQueryHandler(healthEndpoint).handle()).isEqualTo(RadarServiceStatus.UP);
    }

    @Test
    void isDownWhenRedisIsDown() {
        when(healthEndpoint.healthForPath("redis")).thenReturn(Health.down().build());

        assertThat(new GetRadarStatusQueryHandler(healthEndpoint).handle()).isEqualTo(RadarServiceStatus.DOWN);
    }

    @Test
    void isDownWhenThereIsNoRedisHealthComponent() {
        when(healthEndpoint.healthForPath("redis")).thenReturn(null);

        assertThat(new GetRadarStatusQueryHandler(healthEndpoint).handle()).isEqualTo(RadarServiceStatus.DOWN);
    }
}
