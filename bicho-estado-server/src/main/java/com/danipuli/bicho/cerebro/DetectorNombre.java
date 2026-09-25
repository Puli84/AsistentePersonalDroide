package com.danipuli.bicho.cerebro;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;

/**
 * Decide si una frase transcrita va dirigida al robot, es decir, si contiene su nombre.
 *
 * La transcripción no siempre lo escribe igual ("RoboDragón", "Robo dragón", "Robot Dragón",
 * "Rovodragón"...), así que se compara sin mayúsculas, tildes ni espacios y se permiten un par
 * de letras de diferencia.
 */
@Component
public class DetectorNombre {

    private final List<String> nombres;

    public DetectorNombre(@Value("${bicho.activacion.nombres:robodragon}") String nombres) {
        this.nombres = Arrays.stream(nombres.split(","))
                .map(DetectorNombre::normalizar)
                .filter(n -> !n.isEmpty())
                .toList();
    }

    /** true si en el texto aparece alguno de los nombres del robot (o algo muy parecido). */
    public boolean contieneNombre(String texto) {
        String t = normalizar(texto);
        for (String nombre : nombres) {
            int tolerancia = nombre.length() >= 8 ? 2 : 1;
            for (int largo = nombre.length() - tolerancia; largo <= nombre.length() + tolerancia; largo++) {
                for (int i = 0; i + largo <= t.length(); i++) {
                    if (distancia(t.substring(i, i + largo), nombre) <= tolerancia) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Minúsculas, sin tildes y solo letras: "Robo Dragón!" → "robodragon". */
    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String sinTildes = Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinTildes.replaceAll("[^a-zñ]", "");
    }

    /** Distancia de edición (Levenshtein): cuántas letras hay que cambiar para pasar de a a b. */
    static int distancia(String a, String b) {
        int[] anterior = new int[b.length() + 1];
        int[] actual = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            anterior[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            actual[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int coste = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                actual[j] = Math.min(Math.min(actual[j - 1] + 1, anterior[j] + 1), anterior[j - 1] + coste);
            }
            int[] tmp = anterior;
            anterior = actual;
            actual = tmp;
        }
        return anterior[b.length()];
    }
}
