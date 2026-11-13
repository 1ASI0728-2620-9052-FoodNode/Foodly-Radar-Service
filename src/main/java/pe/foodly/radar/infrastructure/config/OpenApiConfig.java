package pe.foodly.radar.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI radarOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Foodly - Geo-Radar Engine Service (GRES)")
                        .version("0.0.1")
                        .description("""
                                Descubrimiento geoespacial de huariques. Indexa la ubicación de cada huarique con \
                                celdas hexagonales H3 y devuelve los establecimientos activos cercanos al \
                                explorador, ordenados por distancia. La distancia se calcula con las coordenadas; \
                                el servicio no calcula rutas ni tiempos de llegada."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT emitido por IAM (firma HMAC, expira a los 60 minutos). "
                                + "Para buscar se requiere el rol GASTRONOMIC_EXPLORER.")));
    }
}
