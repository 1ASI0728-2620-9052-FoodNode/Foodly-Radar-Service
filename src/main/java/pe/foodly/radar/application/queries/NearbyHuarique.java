package pe.foodly.radar.application.queries;

import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.Distance;

/** Resultado de la búsqueda: el huarique y su distancia al explorador, calculada por la capa de aplicación. */
public record NearbyHuarique(HuariqueLocation location, Distance distance) {
}
