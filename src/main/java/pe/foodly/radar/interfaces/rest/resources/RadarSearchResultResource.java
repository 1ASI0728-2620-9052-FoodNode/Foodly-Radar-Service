package pe.foodly.radar.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Huariques activos de la zona de cobertura, ordenados por distancia ascendente.")
public record RadarSearchResultResource(
        @Schema(description = "Huariques encontrados. Lista vacía si la zona no tiene ninguno.")
        List<HuariqueLocationResource> huariques,

        @Schema(description = "Cantidad de huariques devueltos.", example = "1")
        int total) {
}
