package pe.foodly.radar.domain.model.valueobjects;

/**
 * Identificador de la celda hexagonal H3 de una ubicación (5.2.1).
 * El dominio no conoce la librería H3: solo guarda el índice como texto.
 */
public record H3Cell(String index) {

    public H3Cell {
        if (index == null || index.isBlank()) {
            throw new IllegalArgumentException("El índice de la celda H3 no puede estar vacío.");
        }
    }
}
