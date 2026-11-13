package pe.foodly.radar.application.eventhandlers;

import org.springframework.stereotype.Component;
import pe.foodly.radar.application.commandhandlers.UpsertHuariqueLocationCommandHandler;
import pe.foodly.radar.application.commands.UpsertHuariqueLocationCommand;
import pe.foodly.radar.application.events.HuariqueUpdatedEvent;

/** Ejecuta {@link UpsertHuariqueLocationCommand} (5.2.3). */
@Component
public class HuariqueUpdatedEventHandler {

    private final UpsertHuariqueLocationCommandHandler upsertHandler;

    public HuariqueUpdatedEventHandler(UpsertHuariqueLocationCommandHandler upsertHandler) {
        this.upsertHandler = upsertHandler;
    }

    public void handle(HuariqueUpdatedEvent event) {
        upsertHandler.handle(new UpsertHuariqueLocationCommand(
                event.huariqueId(), event.latitude(), event.longitude(), event.status()));
    }
}
