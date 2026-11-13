package pe.foodly.radar.application.exceptions;

import pe.foodly.radar.domain.model.valueobjects.HuariqueId;

/**
 * El huarique no está indexado. Quien invoque el comando decide qué hacer (por ejemplo, el listener
 * reintenta y, tras tres fallos, manda el mensaje a la Dead Letter Queue).
 */
public class HuariqueLocationNotFoundException extends RuntimeException {

    public HuariqueLocationNotFoundException(HuariqueId id) {
        super("El huarique " + id.value() + " no está indexado en el radar.");
    }
}
