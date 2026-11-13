package pe.foodly.radar.interfaces.rest.resources;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.springframework.http.HttpStatus;

/** Formato de error uniforme de los servicios: { status, error, message, path, timestamp }. */
@Schema(description = "Error uniforme del servicio.")
public record ErrorResponse(
        @Schema(description = "Código HTTP.", example = "400") int status,
        @Schema(description = "Descripción estándar del código HTTP.", example = "Bad Request") String error,
        @Schema(description = "Mensaje para el usuario, en español.",
                example = "No se pudo procesar la consulta del radar: la latitud debe estar entre -90 y 90")
        String message,
        @Schema(description = "Ruta solicitada.", example = "/radar/search") String path,
        @Schema(description = "Momento del error (ISO-8601, UTC).", example = "2026-11-13T21:45:00Z")
        Instant timestamp) {

    public static ErrorResponse of(HttpStatus status, String message, String path) {
        return new ErrorResponse(status.value(), status.getReasonPhrase(), message, path, Instant.now());
    }
}
