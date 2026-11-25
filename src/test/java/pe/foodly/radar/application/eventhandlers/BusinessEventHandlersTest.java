package pe.foodly.radar.application.eventhandlers;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pe.foodly.radar.application.commandhandlers.ChangeHuariqueAvailabilityCommandHandler;
import pe.foodly.radar.application.commandhandlers.UpsertHuariqueLocationCommandHandler;
import pe.foodly.radar.application.commands.ChangeHuariqueAvailabilityCommand;
import pe.foodly.radar.application.commands.UpsertHuariqueLocationCommand;
import pe.foodly.radar.application.events.HuariqueRegisteredEvent;
import pe.foodly.radar.application.events.HuariqueUpdatedEvent;
import pe.foodly.radar.application.events.OpeningStatusChangedEvent;
import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;

@ExtendWith(MockitoExtension.class)
class BusinessEventHandlersTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Mock
    private UpsertHuariqueLocationCommandHandler upsertHandler;
    @Mock
    private ChangeHuariqueAvailabilityCommandHandler changeHandler;

    @Test
    void huariqueRegisteredExecutesTheUpsertCommand() {
        new HuariqueRegisteredEventHandler(upsertHandler).handle(
                new HuariqueRegisteredEvent("e-1", "h-1", 1, NOW, -12.1219, -77.0297, AvailabilityStatus.ACTIVE));

        verify(upsertHandler).handle(
                new UpsertHuariqueLocationCommand("h-1", -12.1219, -77.0297, AvailabilityStatus.ACTIVE));
        verifyNoMoreInteractions(upsertHandler);
    }

    @Test
    void huariqueUpdatedExecutesTheUpsertCommand() {
        new HuariqueUpdatedEventHandler(upsertHandler).handle(
                new HuariqueUpdatedEvent("e-2", "h-1", 2, NOW, -12.1300, -77.0300, AvailabilityStatus.INACTIVE));

        verify(upsertHandler).handle(
                new UpsertHuariqueLocationCommand("h-1", -12.1300, -77.0300, AvailabilityStatus.INACTIVE));
        verifyNoMoreInteractions(upsertHandler);
    }

    @Test
    void openingStatusChangedExecutesTheChangeAvailabilityCommand() {
        new OpeningStatusChangedEventHandler(changeHandler).handle(
                new OpeningStatusChangedEvent("e-4", "h-1", 4, NOW, AvailabilityStatus.INACTIVE));

        verify(changeHandler).handle(new ChangeHuariqueAvailabilityCommand("h-1", AvailabilityStatus.INACTIVE));
        verifyNoMoreInteractions(changeHandler);
    }
}
