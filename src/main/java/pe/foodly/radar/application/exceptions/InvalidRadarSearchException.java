package pe.foodly.radar.application.exceptions;

/** La consulta de búsqueda no es válida. El mensaje es el detalle que se le muestra al cliente. */
public class InvalidRadarSearchException extends RuntimeException {

    public InvalidRadarSearchException(String detail) {
        super(detail);
    }
}
