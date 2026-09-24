package com.danipuli.bicho.model;

/**
 * Los tres estados que puede mostrar la cara del bicho.
 * Coinciden con los estados que ya usa el prototipo web (reposo / escuchando / hablando).
 */
public enum Estado {
    REPOSO,
    ESCUCHANDO,
    HABLANDO;

    /**
     * Convierte un texto (por ejemplo el que llega en un JSON) al enum,
     * sin distinguir mayúsculas/minúsculas y sin explotar si viene raro.
     */
    public static Estado desdeTexto(String texto) {
        if (texto == null) {
            return REPOSO;
        }
        try {
            return Estado.valueOf(texto.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return REPOSO;
        }
    }

    public String comoTexto() {
        return name().toLowerCase();
    }
}
