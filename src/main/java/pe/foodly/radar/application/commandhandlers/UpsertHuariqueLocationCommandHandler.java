package pe.foodly.radar.application.commandhandlers;

import org.springframework.stereotype.Component;
import pe.foodly.radar.application.commands.UpsertHuariqueLocationCommand;
import pe.foodly.radar.domain.model.aggregates.HuariqueLocation;
import pe.foodly.radar.domain.model.valueobjects.GeoCoordinates;
import pe.foodly.radar.domain.model.valueobjects.H3Cell;
import pe.foodly.radar.domain.model.valueobjects.HuariqueId;
import pe.foodly.radar.domain.repositories.IHuariqueLocationRepository;
import pe.foodly.radar.domain.services.IH3IndexService;

/**
 * Crea o actualiza el agregado {@link HuariqueLocation}, recalcula su celda H3 y lo guarda.
 * Si la celda cambió, el repositorio lo mueve del índice anterior al nuevo (5.2.3).
 */
@Component
public class UpsertHuariqueLocationCommandHandler {

    private final IHuariqueLocationRepository repository;
    private final IH3IndexService h3IndexService;

    public UpsertHuariqueLocationCommandHandler(IHuariqueLocationRepository repository,
                                                IH3IndexService h3IndexService) {
        this.repository = repository;
        this.h3IndexService = h3IndexService;
    }

    public void handle(UpsertHuariqueLocationCommand command) {
        HuariqueId id = new HuariqueId(command.huariqueId());
        GeoCoordinates coordinates = new GeoCoordinates(command.latitude(), command.longitude());
        H3Cell cell = h3IndexService.toCell(coordinates);

        HuariqueLocation location = repository.findById(id)
                .map(existing -> {
                    existing.updateLocation(coordinates, cell);
                    existing.changeStatus(command.status());
                    return existing;
                })
                .orElseGet(() -> new HuariqueLocation(id, coordinates, cell, command.status()));

        repository.save(location);
    }
}
