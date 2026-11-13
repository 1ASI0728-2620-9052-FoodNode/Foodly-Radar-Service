package pe.foodly.radar.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.foodly.radar.application.queries.RadarServiceStatus;
import pe.foodly.radar.application.queryhandlers.GetRadarStatusQueryHandler;
import pe.foodly.radar.interfaces.rest.resources.RadarStatusResource;

@RestController
@RequestMapping(value = "/radar", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Radar", description = "Búsqueda de huariques cercanos por indexación hexagonal H3.")
public class RadarStatusController {

    private final GetRadarStatusQueryHandler statusHandler;

    public RadarStatusController(GetRadarStatusQueryHandler statusHandler) {
        this.statusHandler = statusHandler;
    }

    @GetMapping("/status")
    @Operation(summary = "Estado del servicio",
            description = "Verifica que Radar está operativo, es decir, que puede atender búsquedas (Redis "
                    + "disponible). Es público: no requiere token.")
    @ApiResponse(responseCode = "200", description = "El servicio está activo.",
            content = @Content(schema = @Schema(implementation = RadarStatusResource.class),
                    examples = @ExampleObject(value = "{ \"status\": \"UP\" }")))
    @ApiResponse(responseCode = "503", description = "El servicio no puede atender búsquedas.",
            content = @Content(schema = @Schema(implementation = RadarStatusResource.class),
                    examples = @ExampleObject(value = "{ \"status\": \"DOWN\" }")))
    public ResponseEntity<RadarStatusResource> status() {
        RadarServiceStatus status = statusHandler.handle();
        HttpStatus http = status == RadarServiceStatus.UP ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(http).body(new RadarStatusResource(status.name()));
    }
}
