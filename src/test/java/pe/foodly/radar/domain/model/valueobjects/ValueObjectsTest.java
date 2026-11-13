package pe.foodly.radar.domain.model.valueobjects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ValueObjectsTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void huariqueIdRejectsBlankValues(String value) {
        assertThatThrownBy(() -> new HuariqueId(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void h3CellRejectsBlankIndex(String index) {
        assertThatThrownBy(() -> new H3Cell(index)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, Double.NaN, Double.POSITIVE_INFINITY})
    void distanceRejectsNegativeOrNonFiniteMeters(double meters) {
        assertThatThrownBy(() -> new Distance(meters)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueObjectsAreComparedByValue() {
        assertThat(new HuariqueId("h-1")).isEqualTo(new HuariqueId("h-1"));
        assertThat(new H3Cell("a")).isEqualTo(new H3Cell("a"));
        assertThat(new Distance(10)).isEqualTo(new Distance(10));
    }

    @Test
    void coverageZoneContainsOnlyItsCells() {
        CoverageZone zone = new CoverageZone(Set.of(new H3Cell("a"), new H3Cell("b")));

        assertThat(zone.contains(new H3Cell("a"))).isTrue();
        assertThat(zone.contains(new H3Cell("c"))).isFalse();
    }

    @Test
    void coverageZoneIsImmutableAndKeepsACopy() {
        Set<H3Cell> source = new HashSet<>(Set.of(new H3Cell("a")));
        CoverageZone zone = new CoverageZone(source);

        source.add(new H3Cell("b"));

        assertThat(zone.cells()).containsExactly(new H3Cell("a"));
        assertThatThrownBy(() -> zone.cells().add(new H3Cell("c")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void coverageZoneRejectsNullCells() {
        assertThatThrownBy(() -> new CoverageZone(null)).isInstanceOf(NullPointerException.class);
    }
}
