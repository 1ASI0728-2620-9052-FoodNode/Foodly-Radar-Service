package pe.foodly.radar.infrastructure.messaging;

import java.util.Arrays;
import java.util.Optional;

/**
 * Eventos de Business que Radar procesa (4.2.5, 5.3.4). Cualquier otro tipo, incluido {@code MenuUpdated},
 * se confirma y se ignora.
 */
public enum BusinessEventType {
    HUARIQUE_REGISTERED("HuariqueRegistered"),
    HUARIQUE_UPDATED("HuariqueUpdated"),
    LOCATION_UPDATED("LocationUpdated"),
    OPENING_STATUS_CHANGED("OpeningStatusChanged");

    private final String eventType;

    BusinessEventType(String eventType) {
        this.eventType = eventType;
    }

    public String eventType() {
        return eventType;
    }

    public static Optional<BusinessEventType> fromEventType(String eventType) {
        return Arrays.stream(values()).filter(type -> type.eventType.equals(eventType)).findFirst();
    }
}
