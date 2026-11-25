package pe.foodly.radar.infrastructure.messaging;

import java.util.Arrays;
import java.util.Optional;

/**
 * Eventos de Business v1 que Radar procesa. El nombre del evento es el de Business (en español, decisión del
 * equipo); los eventos y handlers internos de Radar conservan sus nombres en inglés solo como mapeo interno:
 *
 * <ul>
 *   <li>{@code HuariqueRegistrado} -> {@code HuariqueRegisteredEvent} -> UpsertHuariqueLocationCommand</li>
 *   <li>{@code PerfilDelHuariqueActualizado} -> {@code HuariqueUpdatedEvent} -> UpsertHuariqueLocationCommand
 *       (el cambio de ubicación llega dentro del snapshot de este evento)</li>
 *   <li>{@code EstadoAbiertoCerradoCambiado} -> {@code OpeningStatusChangedEvent} ->
 *       ChangeHuariqueAvailabilityCommand</li>
 * </ul>
 *
 * Cualquier otro tipo ({@code MenuCreado}, {@code MenuActualizado}...) se confirma y se ignora.
 */
public enum BusinessEventType {
    HUARIQUE_REGISTERED("HuariqueRegistrado"),
    HUARIQUE_UPDATED("PerfilDelHuariqueActualizado"),
    OPENING_STATUS_CHANGED("EstadoAbiertoCerradoCambiado");

    private final String eventType;

    BusinessEventType(String eventType) {
        this.eventType = eventType;
    }

    /** Nombre del evento tal como lo publica Business (campo {@code type} del sobre). */
    public String eventType() {
        return eventType;
    }

    /** Routing key con la que Business publica el evento: {@code business.<Tipo>}. */
    public String routingKey() {
        return RabbitMQConfig.ROUTING_KEY_PREFIX + eventType;
    }

    public static Optional<BusinessEventType> fromEventType(String eventType) {
        return Arrays.stream(values()).filter(type -> type.eventType.equals(eventType)).findFirst();
    }
}
