package pe.foodly.radar.domain.model.valueobjects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GeoCoordinatesTest {

    @ParameterizedTest(name = "lat={0}, lng={1} es inválida")
    @CsvSource({
            "90.0001, 0",
            "-90.0001, 0",
            "0, 180.0001",
            "0, -180.0001",
            "NaN, 0",
            "0, NaN",
            "Infinity, 0",
            "0, -Infinity"
    })
    void rejectsInvalidCoordinates(double latitude, double longitude) {
        assertThatThrownBy(() -> new GeoCoordinates(latitude, longitude))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "lat={0}, lng={1} es válida")
    @CsvSource({"90, 180", "-90, -180", "0, 0", "-12.1219, -77.0297"})
    void acceptsCoordinatesInRangeIncludingBounds(double latitude, double longitude) {
        assertThatCode(() -> new GeoCoordinates(latitude, longitude)).doesNotThrowAnyException();
    }

    @Test
    void distanceFromMirafloresToBarrancoMatchesKnownValue() {
        // Parque Kennedy (Miraflores) y Parque Municipal de Barranco, coordenadas aproximadas.
        // 3252.96 m calculado de forma independiente con haversine (radio 6 371 008,8 m).
        GeoCoordinates miraflores = new GeoCoordinates(-12.1219, -77.0297);
        GeoCoordinates barranco = new GeoCoordinates(-12.1498, -77.0207);

        double meters = miraflores.distanceTo(barranco).meters();

        assertThat(meters).isCloseTo(3252.96, within(1.0));
    }

    @Test
    void oneDegreeOfLongitudeAtTheEquatorIsAbout111Km() {
        double meters = new GeoCoordinates(0, 0).distanceTo(new GeoCoordinates(0, 1)).meters();

        assertThat(meters).isCloseTo(111_195.08, within(0.5));
    }

    @Test
    void distanceToSamePointIsZero() {
        GeoCoordinates point = new GeoCoordinates(-12.1219, -77.0297);

        assertThat(point.distanceTo(point).meters()).isZero();
    }

    @Test
    void distanceIsSymmetric() {
        GeoCoordinates miraflores = new GeoCoordinates(-12.1219, -77.0297);
        GeoCoordinates barranco = new GeoCoordinates(-12.1498, -77.0207);

        assertThat(miraflores.distanceTo(barranco).meters())
                .isCloseTo(barranco.distanceTo(miraflores).meters(), within(1e-6));
    }

    @Test
    void distanceBetweenAntipodesDoesNotFail() {
        double meters = new GeoCoordinates(0, 0).distanceTo(new GeoCoordinates(0, 180)).meters();

        assertThat(meters).isCloseTo(Math.PI * 6_371_008.8, within(1.0));
    }

    @Test
    void distanceToNullIsRejected() {
        assertThatThrownBy(() -> new GeoCoordinates(0, 0).distanceTo(null))
                .isInstanceOf(NullPointerException.class);
    }
}
