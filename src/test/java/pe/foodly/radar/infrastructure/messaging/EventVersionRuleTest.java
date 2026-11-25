package pe.foodly.radar.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventVersionRuleTest {

    @ParameterizedTest(name = "almacenada {0}, evento {1} => descartado")
    @CsvSource({"5, 5", "5, 4", "5, 0", "0, 0", "10, 3"})
    void aVersionLowerOrEqualToTheStoredOneIsStale(long stored, long event) {
        assertThat(EventVersionRule.isStale(Optional.of(stored), event)).isTrue();
    }

    @ParameterizedTest(name = "almacenada {0}, evento {1} => se aplica")
    @CsvSource({"5, 6", "0, 1", "5, 100"})
    void aHigherVersionIsApplied(long stored, long event) {
        assertThat(EventVersionRule.isStale(Optional.of(stored), event)).isFalse();
    }

    /** La aggregateVersion de Business empieza en 0: ese primer evento nunca es obsoleto. */
    @Test
    void theFirstEventWithVersionZeroIsNotStaleWhenNothingIsStored() {
        assertThat(EventVersionRule.isStale(Optional.empty(), 0)).isFalse();
    }

    @Test
    void withNoStoredVersionEveryEventIsApplied() {
        assertThat(EventVersionRule.isStale(Optional.empty(), 0)).isFalse();
        assertThat(EventVersionRule.isStale(Optional.empty(), 7)).isFalse();
    }
}
