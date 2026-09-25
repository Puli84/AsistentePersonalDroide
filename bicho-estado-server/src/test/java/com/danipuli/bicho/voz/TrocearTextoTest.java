package com.danipuli.bicho.voz;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Los textos largos se parten por frases para generar la voz a trozos. */
class TrocearTextoTest {

    @Test
    void unaRespuestaCortaVaEnUnSoloTrozo() {
        assertEquals(List.of("Hola, ¿qué tal? Aquí estoy."), OpenAiVozClient.trocear("Hola, ¿qué tal? Aquí estoy."));
    }

    @Test
    void unCuentoLargoSeParteSinCortarFrasesNiPerderTexto() {
        String frase = "Había una vez un robot pequeñito que soñaba con bailar por todo el salón. ";
        String cuento = frase.repeat(20).strip();

        List<String> trozos = OpenAiVozClient.trocear(cuento);

        assertTrue(trozos.size() > 1);
        for (String trozo : trozos) {
            assertTrue(trozo.length() <= OpenAiVozClient.MAX_CARACTERES_TROZO, trozo);
            assertTrue(trozo.endsWith("salón."), trozo);
        }
        assertEquals(cuento, String.join(" ", trozos));
    }
}
