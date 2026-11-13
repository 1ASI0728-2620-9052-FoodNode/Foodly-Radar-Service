package pe.foodly.radar.application.commands;

import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;

/** Cambia el estado de disponibilidad de un huarique ya indexado (5.2.3). */
public record ChangeHuariqueAvailabilityCommand(String huariqueId, AvailabilityStatus status) {
}
