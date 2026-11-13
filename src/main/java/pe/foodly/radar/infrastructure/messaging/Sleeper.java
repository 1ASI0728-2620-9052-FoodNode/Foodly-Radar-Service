package pe.foodly.radar.infrastructure.messaging;

/** Espera entre reintentos. Es una interfaz para poder probar el reintento sin dormir de verdad. */
@FunctionalInterface
public interface Sleeper {

    void sleep(long millis) throws InterruptedException;
}
