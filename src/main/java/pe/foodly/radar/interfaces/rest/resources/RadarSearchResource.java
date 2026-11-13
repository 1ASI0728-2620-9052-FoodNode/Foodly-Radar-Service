package pe.foodly.radar.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Datos de la búsqueda de huariques cercanos.")
public record RadarSearchResource(
        @Schema(description = "Latitud del explorador, entre -90 y 90.", example = "-12.1219")
        @NotNull(message = "la latitud es obligatoria")
        @DecimalMin(value = "-90.0", message = "la latitud debe estar entre -90 y 90")
        @DecimalMax(value = "90.0", message = "la latitud debe estar entre -90 y 90")
        Double latitude,

        @Schema(description = "Longitud del explorador, entre -180 y 180.", example = "-77.0297")
        @NotNull(message = "la longitud es obligatoria")
        @DecimalMin(value = "-180.0", message = "la longitud debe estar entre -180 y 180")
        @DecimalMax(value = "180.0", message = "la longitud debe estar entre -180 y 180")
        Double longitude,

        @Schema(description = "Número de anillos H3 alrededor de la celda del explorador, de 0 hasta el máximo "
                + "configurado (RADAR_MAX_K_RING, por defecto 5). Con más anillos la zona de búsqueda es mayor.",
                example = "2")
        @NotNull(message = "el k-ring es obligatorio")
        @Min(value = 0, message = "el k-ring no puede ser negativo")
        Integer kRing) {
}
