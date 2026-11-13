package pe.foodly.radar.domain.model.valueobjects;

/** Identificador del huarique, emitido por Business (5.2.1). */
public record HuariqueId(String value) {

    public HuariqueId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El identificador del huarique no puede estar vacío.");
        }
    }
}
