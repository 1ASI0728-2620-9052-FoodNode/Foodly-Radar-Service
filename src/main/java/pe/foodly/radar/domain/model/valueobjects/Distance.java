package pe.foodly.radar.domain.model.valueobjects;

/** Distancia en metros entre el explorador y un huarique (5.2.1). */
public record Distance(double meters) {

    public Distance {
        if (Double.isNaN(meters) || Double.isInfinite(meters) || meters < 0) {
            throw new IllegalArgumentException("La distancia debe ser un número finito mayor o igual a 0.");
        }
    }
}
