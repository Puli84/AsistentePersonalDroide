package com.danipuli.bicho.cerebro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectorNombreTest {

    private final DetectorNombre detector = new DetectorNombre("RoboDragón");

    @Test
    void reconoceElNombreEscritoDeVariasFormas() {
        assertTrue(detector.contieneNombre("RoboDragón, ¿qué hora es?"));
        assertTrue(detector.contieneNombre("Robo dragón, avanza un poco."));
        assertTrue(detector.contieneNombre("Robot Dragón, baila"));
        assertTrue(detector.contieneNombre("robodragon"));
        assertTrue(detector.contieneNombre("Oye, Rovodragón, ¿me oyes?"));
        assertTrue(detector.contieneNombre("¿Qué tal estás, RoboDragón?"));
    }

    @Test
    void noSeActivaConFrasesNormales() {
        assertFalse(detector.contieneNombre("Gracias por ver el vídeo."));
        assertFalse(detector.contieneNombre("¿Has visto la película del dragón?"));
        assertFalse(detector.contieneNombre("Pásame el mando de la tele"));
        assertFalse(detector.contieneNombre("El robot de cocina está roto"));
        assertFalse(detector.contieneNombre(""));
        assertFalse(detector.contieneNombre(null));
    }
}
