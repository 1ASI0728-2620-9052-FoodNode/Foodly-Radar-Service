package pe.foodly.radar.application.eventhandlers;

import org.springframework.stereotype.Component;
import pe.foodly.radar.application.commandhandlers.ChangeHuariqueAvailabilityCommandHandler;
import pe.foodly.radar.application.commands.ChangeHuariqueAvailabilityCommand;
import pe.foodly.radar.application.events.OpeningStatusChangedEvent;

/** Ejecuta {@link ChangeHuariqueAvailabilityCommand} (5.2.3). */
@Component
public class OpeningStatusChangedEventHandler {

    private final ChangeHuariqueAvailabilityCommandHandler changeHandler;

    public OpeningStatusChangedEventHandler(ChangeHuariqueAvailabilityCommandHandler changeHandler) {
        this.changeHandler = changeHandler;
    }

    public void handle(OpeningStatusChangedEvent event) {
        changeHandler.handle(new ChangeHuariqueAvailabilityCommand(event.huariqueId(), event.status()));
    }
}
