package pe.foodly.radar.application.queries;

/** Búsqueda por proximidad: latitud, longitud y número de anillos H3 (5.2.3). */
public record SearchNearbyHuariquesQuery(double latitude, double longitude, int kRing) {
}
