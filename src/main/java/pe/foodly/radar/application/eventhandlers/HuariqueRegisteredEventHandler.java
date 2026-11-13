package pe.foodly.radar.application.eventhandlers;

import org.springframework.stereotype.Component;
import pe.foodly.radar.application.commandhandlers.UpsertHuariqueLocationCommandHandler;
import pe.foodly.radar.application.commands.UpsertHuariqueLocationCommand;
import pe.foodly.radar.application.events.HuariqueRegisteredEvent;

/** Ejecuta {@link UpsertHuariqueLocationCommand} (5.2.3). */
@Component
public class HuariqueRegisteredEventHandler {

    private final UpsertHuariqueLocationCommandHandler upsertHandler;

    public HuariqueRegisteredEventHandler(UpsertHuariqueLocationCommandHandler upsertHandler) {
        this.upsertHandler = upsertHandler;
    }

    public void handle(HuariqueRegisteredEvent event) {
        upsertHandler.handle(new UpsertHuariqueLocationCommand(
                event.huariqueId(), event.latitude(), event.longitude(), event.status()));
    }
}
