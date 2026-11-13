package pe.foodly.radar.application.commandhandlers;

import org.springframework.stereotype.Component;
import pe.foodly.radar.application.commands.RemoveHuariqueLocationCommand;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;

/** Elimina la ubicación indexada (5.2.3). Es idempotente: borrar un huarique ausente no es un error. */
@Component
public class RemoveHuariqueLocationCommandHandler {

    private final IHuariqueLocationRepository repository;

    public RemoveHuariqueLocationCommandHandler(IHuariqueLocationRepository repository) {
        this.repository = repository;
    }

    public void handle(RemoveHuariqueLocationCommand command) {
        repository.remove(new HuariqueId(command.huariqueId()));
    }
}
