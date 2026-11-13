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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.foodly.radar.application.commands.ChangeHuariqueAvailabilityCommand;
import pe.foodly.radar.application.commands.RemoveHuariqueLocationCommand;
import pe.foodly.radar.application.exceptions.HuariqueLocationNotFoundException;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;

@ExtendWith(MockitoExtension.class)
class ChangeAndRemoveCommandHandlersTest {

    private static final HuariqueId ID = new HuariqueId("h-1");

    @Mock
    private IHuariqueLocationRepository repository;

    private HuariqueLocation active() {
        return new HuariqueLocation(ID, new GeoCoordinates(-12.1219, -77.0297), new H3Cell("cell-a"),
                AvailabilityStatus.ACTIVE);
    }

    @Test
    void changeAvailabilityUpdatesTheStatusAndSaves() {
        HuariqueLocation location = active();
        when(repository.findById(ID)).thenReturn(Optional.of(location));

        new ChangeHuariqueAvailabilityCommandHandler(repository)
                .handle(new ChangeHuariqueAvailabilityCommand("h-1", AvailabilityStatus.INACTIVE));

        assertThat(location.isActive()).isFalse();
        verify(repository).save(location);
    }

    @Test
    void changeAvailabilityFailsWhenTheHuariqueIsNotIndexed() {
        when(repository.findById(ID)).thenReturn(Optional.empty());
        ChangeHuariqueAvailabilityCommandHandler handler = new ChangeHuariqueAvailabilityCommandHandler(repository);

        assertThatThrownBy(() -> handler.handle(
                new ChangeHuariqueAvailabilityCommand("h-1", AvailabilityStatus.INACTIVE)))
                .isInstanceOf(HuariqueLocationNotFoundException.class)
                .hasMessageContaining("h-1");

        verify(repository, never()).save(any());
    }

    @Test
    void removeDeletesTheHuarique() {
        new RemoveHuariqueLocationCommandHandler(repository).handle(new RemoveHuariqueLocationCommand("h-1"));

        verify(repository).remove(ID);
    }

    @Test
    void removeRejectsABlankId() {
        RemoveHuariqueLocationCommandHandler handler = new RemoveHuariqueLocationCommandHandler(repository);

        assertThatThrownBy(() -> handler.handle(new RemoveHuariqueLocationCommand("")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).remove(any());
    }
}
