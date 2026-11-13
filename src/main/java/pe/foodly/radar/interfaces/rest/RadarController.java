package pe.foodly.radar.interfaces.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.foodly.radar.application.queryhandlers.SearchNearbyHuariquesQueryHandler;
import pe.foodly.radar.interfaces.rest.resources.ErrorResponse;
import pe.foodly.radar.interfaces.rest.resources.RadarSearchResource;
import pe.foodly.radar.interfaces.rest.resources.RadarSearchResultResource;
import pe.foodly.radar.interfaces.rest.transform.RadarSearchResultResourceFromEntitiesAssembler;
import pe.foodly.radar.interfaces.rest.transform.SearchNearbyHuariquesQueryFromResourceAssembler;

@RestController
@RequestMapping(value = "/radar", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Radar", description = "Búsqueda de huariques cercanos por indexación hexagonal H3.")
public class RadarController {

    private final SearchNearbyHuariquesQueryHandler searchHandler;

    public RadarController(SearchNearbyHuariquesQueryHandler searchHandler) {
        this.searchHandler = searchHandler;
    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Buscar huariques cercanos",
            description = "Devuelve los huariques activos de la zona de cobertura del explorador (su celda H3 más "
                    + "el k-ring indicado), ordenados por distancia ascendente. La distancia sale de las "
                    + "coordenadas y no considera rutas. Si la zona no tiene huariques responde 200 con una lista "
                    + "vacía. Requiere el rol GASTRONOMIC_EXPLORER.")
    @ApiResponse(responseCode = "200", description = "Huariques de la zona, posiblemente una lista vacía.",
            content = @Content(schema = @Schema(implementation = RadarSearchResultResource.class),
                    examples = {
                            @ExampleObject(name = "Con resultados", value = """
                                    {
                                      "huariques": [
                                        { "huariqueId": "65f1c2a9e4b0a1d2c3f4a5b6", "latitude": -12.1229,
                                          "longitude": -77.0297, "distanceMeters": 111.2 }
                                      ],
                                      "total": 1
                                    }"""),
                            @ExampleObject(name = "Zona sin huariques", value = """
                                    { "huariques": [], "total": 0 }""")
                    }))
    @ApiResponse(responseCode = "400", description = "Coordenadas fuera de rango, k-ring inválido o cuerpo mal formado.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "status": 400,
                              "error": "Bad Request",
                              "message": "No se pudo procesar la consulta del radar: la latitud debe estar entre -90 y 90",
                              "path": "/radar/search",
                              "timestamp": "2026-11-13T21:45:00Z"
                            }""")))
    @ApiResponse(responseCode = "401", description = "Falta el token o no es válido.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "El rol del token no es GASTRONOMIC_EXPLORER.",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<RadarSearchResultResource> search(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Ubicación del explorador y número de anillos H3.",
                    content = @Content(examples = @ExampleObject(value = """
                            { "latitude": -12.1219, "longitude": -77.0297, "kRing": 2 }""")))
            @Valid @RequestBody RadarSearchResource resource) {
        var results = searchHandler.handle(SearchNearbyHuariquesQueryFromResourceAssembler.toQuery(resource));
        return ResponseEntity.ok(RadarSearchResultResourceFromEntitiesAssembler.toResource(results));
    }
}
