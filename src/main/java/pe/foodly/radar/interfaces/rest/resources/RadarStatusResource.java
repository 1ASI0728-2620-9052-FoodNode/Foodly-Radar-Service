package pe.foodly.radar.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Estado operativo del servicio.")
public record RadarStatusResource(
        @Schema(description = "UP si Radar puede atender búsquedas; DOWN en caso contrario.", example = "UP")
        String status) {
}
