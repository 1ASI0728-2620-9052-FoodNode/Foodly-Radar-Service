package pe.foodly.radar.application.eventhandlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import pe.foodly.radar.domain.model.events.DomainEvent;

/**
 * Los domain events de Radar son internos: solo se registran en el log. No se publican en RabbitMQ.
 */
@Component
public class RadarDomainEventLogger {

    private static final Logger log = LoggerFactory.getLogger(RadarDomainEventLogger.class);

    @EventListener
    public void on(DomainEvent event) {
        log.info("Evento de dominio: {}", event);
    }
}
