package pe.foodly.radar.application.commandhandlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.foodly.radar.application.commands.UpsertHuariqueLocationCommand;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;
import pe.foodly.radar.domain.services.IH3IndexService;

@ExtendWith(MockitoExtension.class)
class UpsertHuariqueLocationCommandHandlerTest {

    private static final HuariqueId ID = new HuariqueId("h-1");
    private static final GeoCoordinates MIRAFLORES = new GeoCoordinates(-12.1219, -77.0297);
    private static final GeoCoordinates BARRANCO = new GeoCoordinates(-12.1498, -77.0207);

    @Mock
    private IHuariqueLocationRepository repository;
    @Mock
    private IH3IndexService h3IndexService;

    private UpsertHuariqueLocationCommandHandler handler() {
        return new UpsertHuariqueLocationCommandHandler(repository, h3IndexService);
    }

    @Test
    void createsTheAggregateWithTheRecalculatedCellWhenTheHuariqueIsNew() {
        when(repository.findById(ID)).thenReturn(Optional.empty());
        when(h3IndexService.toCell(MIRAFLORES)).thenReturn(new H3Cell("cell-a"));

        handler().handle(new UpsertHuariqueLocationCommand("h-1", -12.1219, -77.0297, AvailabilityStatus.ACTIVE));

        ArgumentCaptor<HuariqueLocation> saved = ArgumentCaptor.forClass(HuariqueLocation.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getHuariqueId()).isEqualTo(ID);
        assertThat(saved.getValue().getCoordinates()).isEqualTo(MIRAFLORES);
        assertThat(saved.getValue().getH3Cell()).isEqualTo(new H3Cell("cell-a"));
        assertThat(saved.getValue().isActive()).isTrue();
    }

    @Test
    void updatesLocationCellAndStatusWhenTheHuariqueAlreadyExists() {
        HuariqueLocation existing = new HuariqueLocation(ID, MIRAFLORES, new H3Cell("cell-a"), AvailabilityStatus.ACTIVE);
        when(repository.findById(ID)).thenReturn(Optional.of(existing));
        when(h3IndexService.toCell(BARRANCO)).thenReturn(new H3Cell("cell-b"));

        handler().handle(new UpsertHuariqueLocationCommand("h-1", -12.1498, -77.0207, AvailabilityStatus.INACTIVE));

        verify(repository).save(existing);
        assertThat(existing.getCoordinates()).isEqualTo(BARRANCO);
        assertThat(existing.getH3Cell()).isEqualTo(new H3Cell("cell-b"));
        assertThat(existing.getStatus()).isEqualTo(AvailabilityStatus.INACTIVE);
    }

    @Test
    void rejectsInvalidCoordinatesWithoutSaving() {
        assertThatThrownBy(() -> handler().handle(
                new UpsertHuariqueLocationCommand("h-1", 95, -77.0297, AvailabilityStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsABlankHuariqueIdWithoutSaving() {
        assertThatThrownBy(() -> handler().handle(
                new UpsertHuariqueLocationCommand(" ", -12.1219, -77.0297, AvailabilityStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(any());
    }
}
