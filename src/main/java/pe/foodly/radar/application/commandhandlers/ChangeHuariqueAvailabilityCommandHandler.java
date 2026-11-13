package pe.foodly.radar.application.commandhandlers;

import org.springframework.stereotype.Component;
import pe.foodly.radar.application.commands.ChangeHuariqueAvailabilityCommand;
import pe.foodly.radar.application.exceptions.HuariqueLocationNotFoundException;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;

/** Actualiza el estado del agregado (5.2.3). Falla si el huarique no está indexado. */
@Component
public class ChangeHuariqueAvailabilityCommandHandler {

    private final IHuariqueLocationRepository repository;

    public ChangeHuariqueAvailabilityCommandHandler(IHuariqueLocationRepository repository) {
        this.repository = repository;
    }

    public void handle(ChangeHuariqueAvailabilityCommand command) {
        HuariqueId id = new HuariqueId(command.huariqueId());
        HuariqueLocation location = repository.findById(id)
                .orElseThrow(() -> new HuariqueLocationNotFoundException(id));

        location.changeStatus(command.status());
        repository.save(location);
    }
}
