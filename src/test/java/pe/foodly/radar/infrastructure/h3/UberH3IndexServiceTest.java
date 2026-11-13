package pe.foodly.radar.infrastructure.h3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uber.h3core.H3Core;
import java.io.IOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pe.foodly.radar.domain.model.valueobjects.CoverageZone;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;

class UberH3IndexServiceTest {

    private static final GeoCoordinates MIRAFLORES = new GeoCoordinates(-12.1219, -77.0297);
    /** Unos 111 m al sur de Miraflores. */
    private static final GeoCoordinates NEAR_MIRAFLORES = new GeoCoordinates(-12.1229, -77.0297);

    private static H3Core h3;

    @BeforeAll
    static void loadH3() throws IOException {
        h3 = H3Core.newInstance();
    }

    @Test
    void toCellUsesTheConfiguredResolution() {
        H3Cell cell = new UberH3IndexService(9).toCell(MIRAFLORES);

        assertThat(h3.isValidCell(cell.index())).isTrue();
        assertThat(h3.getResolution(cell.index())).isEqualTo(9);
    }

    @Test
    void toCellIsDeterministicAndMatchesTheLibrary() {
        UberH3IndexService service = new UberH3IndexService(9);

        assertThat(service.toCell(MIRAFLORES)).isEqualTo(service.toCell(MIRAFLORES));
        assertThat(service.toCell(MIRAFLORES).index())
                .isEqualTo(h3.latLngToCellAddress(MIRAFLORES.latitude(), MIRAFLORES.longitude(), 9));
    }

    @Test
    void differentResolutionsGiveDifferentCells() {
        assertThat(new UberH3IndexService(8).toCell(MIRAFLORES))
                .isNotEqualTo(new UberH3IndexService(9).toCell(MIRAFLORES));
    }

    @Test
    void kRingZeroIsOnlyTheCenterCell() {
        UberH3IndexService service = new UberH3IndexService(9);
        H3Cell center = service.toCell(MIRAFLORES);

        assertThat(service.kRing(center, 0).cells()).containsExactly(center);
    }

    @Test
    void kRingGrowsAsAHexagonalDisk() {
        UberH3IndexService service = new UberH3IndexService(9);
        H3Cell center = service.toCell(MIRAFLORES);

        CoverageZone ringOne = service.kRing(center, 1);
        CoverageZone ringTwo = service.kRing(center, 2);

        assertThat(ringOne.cells()).hasSize(7);
        assertThat(ringTwo.cells()).hasSize(19);
        assertThat(ringOne.contains(center)).isTrue();
        assertThat(ringTwo.cells()).containsAll(ringOne.cells());
    }

    @Test
    void aNearbyPointFallsInsideTheOneRingZone() {
        UberH3IndexService service = new UberH3IndexService(9);
        CoverageZone zone = service.kRing(service.toCell(MIRAFLORES), 1);

        assertThat(zone.contains(service.toCell(NEAR_MIRAFLORES))).isTrue();
    }

    @Test
    void negativeKRingIsRejected() {
        UberH3IndexService service = new UberH3IndexService(9);

        assertThatThrownBy(() -> service.kRing(service.toCell(MIRAFLORES), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 16})
    void resolutionOutOfRangeIsRejectedAtStartup(int resolution) {
        assertThatThrownBy(() -> new UberH3IndexService(resolution))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
