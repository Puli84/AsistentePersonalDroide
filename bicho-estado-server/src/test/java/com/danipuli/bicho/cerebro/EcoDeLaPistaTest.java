package com.danipuli.bicho.cerebro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Con audio que no se entiende, la transcripción devuelve la pista tal cual; eso no debe
 * despertar al robot, pero decir su nombre a secas sí.
 */
class EcoDeLaPistaTest {

    private final ConversacionEsp32 conversacion = new ConversacionEsp32(null, null, null, null, 8,
            "Hablando con RoboDragón, el robot de Daniel.");

    @Test
    void laPistaRepetidaEsEco() {
        assertTrue(conversacion.esEcoDeLaPista("Hablando con RoboDragón, el robot de Daniel."));
        assertTrue(conversacion.esEcoDeLaPista("hablando con robodragon el robot de daniel"));
        assertTrue(conversacion.esEcoDeLaPista("RoboDragón, el robot de Daniel."));
    }

    @Test
    void loQueDicesDeVerdadNoEsEco() {
        assertFalse(conversacion.esEcoDeLaPista("RoboDragón"));
        assertFalse(conversacion.esEcoDeLaPista("RoboDragón, ¿qué hora es?"));
        assertFalse(conversacion.esEcoDeLaPista(""));
    }
}
