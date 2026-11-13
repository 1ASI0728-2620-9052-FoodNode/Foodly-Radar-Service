package pe.foodly.radar.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Un huarique encontrado dentro de la zona de cobertura.")
public record HuariqueLocationResource(
        @Schema(description = "Identificador del huarique, emitido por Business.", example = "65f1c2a9e4b0a1d2c3f4a5b6")
        String huariqueId,

        @Schema(description = "Latitud del huarique.", example = "-12.1229")
        double latitude,

        @Schema(description = "Longitud del huarique.", example = "-77.0297")
        double longitude,

        @Schema(description = "Distancia en metros desde el explorador, calculada con las coordenadas "
                + "(no es una distancia por ruta).", example = "111.2")
        double distanceMeters) {
}
