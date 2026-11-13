package pe.foodly.radar.application.eventhandlers;

import org.springframework.stereotype.Component;
import pe.foodly.radar.application.commandhandlers.UpsertHuariqueLocationCommandHandler;
import pe.foodly.radar.application.commands.UpsertHuariqueLocationCommand;
import pe.foodly.radar.application.events.LocationUpdatedEvent;

/** Ejecuta {@link UpsertHuariqueLocationCommand} con las nuevas coordenadas (5.2.3). */
@Component
public class LocationUpdatedEventHandler {

    private final UpsertHuariqueLocationCommandHandler upsertHandler;

    public LocationUpdatedEventHandler(UpsertHuariqueLocationCommandHandler upsertHandler) {
        this.upsertHandler = upsertHandler;
    }

    public void handle(LocationUpdatedEvent event) {
        upsertHandler.handle(new UpsertHuariqueLocationCommand(
                event.huariqueId(), event.latitude(), event.longitude(), event.status()));
    }
}
