package pe.foodly.radar.domain.model.events;

import java.time.Instant;

/**
 * Marca común de los eventos de dominio de Radar. Son internos del proceso: la capa de aplicación
 * los despacha con ApplicationEventPublisher y solo se registran en log y métricas (no van a RabbitMQ).
 */
public interface DomainEvent {

    Instant occurredAt();
}
