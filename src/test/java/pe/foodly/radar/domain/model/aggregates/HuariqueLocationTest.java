package pe.foodly.radar.domain.model.aggregates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;

class HuariqueLocationTest {

    private HuariqueLocation active() {
        return new HuariqueLocation(new HuariqueId("h-1"), new GeoCoordinates(-12.1219, -77.0297),
                new H3Cell("cell-a"), AvailabilityStatus.ACTIVE);
    }

    @Test
    void isActiveReflectsStatus() {
        HuariqueLocation location = active();

        assertThat(location.isActive()).isTrue();
        location.changeStatus(AvailabilityStatus.INACTIVE);
        assertThat(location.isActive()).isFalse();
        location.changeStatus(AvailabilityStatus.ACTIVE);
        assertThat(location.isActive()).isTrue();
    }

    @Test
    void updateLocationReplacesCoordinatesAndCell() {
        HuariqueLocation location = active();
        GeoCoordinates barranco = new GeoCoordinates(-12.1498, -77.0207);

        location.updateLocation(barranco, new H3Cell("cell-b"));

        assertThat(location.getCoordinates()).isEqualTo(barranco);
        assertThat(location.getH3Cell()).isEqualTo(new H3Cell("cell-b"));
        assertThat(location.getHuariqueId()).isEqualTo(new HuariqueId("h-1"));
        assertThat(location.getStatus()).isEqualTo(AvailabilityStatus.ACTIVE);
    }

    @Test
    void updateLocationRejectsNulls() {
        HuariqueLocation location = active();

        assertThatThrownBy(() -> location.updateLocation(null, new H3Cell("cell-b")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> location.updateLocation(new GeoCoordinates(0, 0), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void changeStatusRejectsNull() {
        assertThatThrownBy(() -> active().changeStatus(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void identityIsTheHuariqueId() {
        HuariqueLocation first = active();
        HuariqueLocation sameIdOtherData = new HuariqueLocation(new HuariqueId("h-1"),
                new GeoCoordinates(0, 0), new H3Cell("cell-z"), AvailabilityStatus.INACTIVE);
        HuariqueLocation otherId = new HuariqueLocation(new HuariqueId("h-2"),
                new GeoCoordinates(-12.1219, -77.0297), new H3Cell("cell-a"), AvailabilityStatus.ACTIVE);

        assertThat(first).isEqualTo(sameIdOtherData).hasSameHashCodeAs(sameIdOtherData);
        assertThat(first).isNotEqualTo(otherId);
    }
}
