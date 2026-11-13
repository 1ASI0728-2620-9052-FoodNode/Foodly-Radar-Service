package pe.foodly.radar.application.commands;

import pe.foodly.radar.domain.model.valueobjects.AvailabilityStatus;

/** Se usa al registrar un huarique o cambiar su ubicación (5.2.3). */
public record UpsertHuariqueLocationCommand(String huariqueId, double latitude, double longitude,
                                            AvailabilityStatus status) {
}
