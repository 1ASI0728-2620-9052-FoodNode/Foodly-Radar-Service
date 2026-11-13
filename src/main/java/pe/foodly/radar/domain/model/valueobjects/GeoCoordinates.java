package pe.foodly.radar.domain.model.valueobjects;

import java.util.Objects;

/**
 * Latitud y longitud (5.2.1). Valida latitud entre -90 y 90 y longitud entre -180 y 180.
 * La distancia entre dos puntos se calcula con la fórmula de haversine (DD-05): no considera rutas.
 */
public record GeoCoordinates(double latitude, double longitude) {

    /** Radio medio de la Tierra en metros (IUGG). */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    public GeoCoordinates {
        // La forma negada también rechaza NaN.
        if (!(latitude >= -90.0 && latitude <= 90.0)) {
            throw new IllegalArgumentException("La latitud debe estar entre -90 y 90.");
        }
        if (!(longitude >= -180.0 && longitude <= 180.0)) {
            throw new IllegalArgumentException("La longitud debe estar entre -180 y 180.");
        }
    }

    public Distance distanceTo(GeoCoordinates other) {
        Objects.requireNonNull(other, "Las coordenadas de destino son obligatorias.");
        double lat1 = Math.toRadians(latitude);
        double lat2 = Math.toRadians(other.latitude);
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(other.longitude - longitude);

        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLng / 2), 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1 - a)));
        return new Distance(EARTH_RADIUS_METERS * c);
    }
}
