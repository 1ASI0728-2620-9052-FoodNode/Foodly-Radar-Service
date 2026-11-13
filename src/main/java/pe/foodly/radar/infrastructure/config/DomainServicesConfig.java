package pe.foodly.radar.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;
import pe.foodly.radar.domain.services.IH3IndexService;
import pe.foodly.radar.domain.services.RadarSearchService;

/** El dominio no conoce Spring, así que sus servicios se registran como beans desde infraestructura. */
@Configuration
public class DomainServicesConfig {

    @Bean
    public RadarSearchService radarSearchService(IHuariqueLocationRepository repository,
                                                 IH3IndexService h3IndexService, Clock clock) {
        return new RadarSearchService(repository, h3IndexService, clock);
    }
}
