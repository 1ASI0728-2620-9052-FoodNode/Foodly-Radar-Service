package pe.foodly.radar.infrastructure.messaging;

/** El mensaje no tiene el formato esperado. Reintentarlo no lo arregla: va directo a la DLQ. */
public class MalformedBusinessEventException extends RuntimeException {

    public MalformedBusinessEventException(String message) {
        super(message);
    }

    public MalformedBusinessEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
