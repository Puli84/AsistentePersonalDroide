package com.danipuli.bicho.cerebro;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La memoria por secciones: apuntar en su sitio, corregir y olvidar solo si encaja uno. */
class MemoriaLargoPlazoTest {

    @TempDir
    Path carpeta;

    private MemoriaLargoPlazo memoria(String contenido) throws Exception {
        Path f = carpeta.resolve("memoria.txt");
        Files.writeString(f, contenido, StandardCharsets.UTF_8);
        return new MemoriaLargoPlazo(f);
    }

    private String fichero() throws Exception {
        return Files.readString(carpeta.resolve("memoria.txt"), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void apuntaEnSuSeccion() throws Exception {
        MemoriaLargoPlazo m = memoria("# Leo\n- Leo tiene 7 años.\n\n# Pendiente\n- Comprar pan\n");

        m.recordar("Leo", "Leo es del Barça.");
        m.recordar("pendiente", "Comprar leche");

        assertEquals("# Leo\n- Leo tiene 7 años.\n- Leo es del Barça.\n\n# Pendiente\n- Comprar pan\n- Comprar leche\n",
                fichero());
    }

    @Test
    void creaLaSeccionSiNoExisteYUsaOtrosSiNoLaConoce() throws Exception {
        MemoriaLargoPlazo m = memoria("# Leo\n- Leo tiene 7 años.\n");

        m.recordar("Casa", "La wifi es de 2,4 GHz");
        m.recordar("cualquier cosa", "Un dato suelto");

        assertEquals("# Leo\n- Leo tiene 7 años.\n\n# Casa\n- La wifi es de 2,4 GHz\n\n# Otros\n- Un dato suelto\n",
                fichero());
    }

    @Test
    void noApuntaDosVecesLoMismo() throws Exception {
        MemoriaLargoPlazo m = memoria("# Pendiente\n- Comprar pan\n");
        assertEquals("Eso ya lo tenías apuntado.", m.recordar("Pendiente", "comprar pan"));
    }

    @Test
    void corrigeYOlvidaSinImportarTildesNiMayusculas() throws Exception {
        MemoriaLargoPlazo m = memoria("# Pendiente\n- Hay que comprar papel higiénico\n- Comprar pan\n");

        assertTrue(m.corregir("PAN", "Comprar pan integral").startsWith("Corregido"));
        assertTrue(m.olvidar("higienico").startsWith("Olvidado"));

        assertEquals("# Pendiente\n- Comprar pan integral\n", fichero());
    }

    @Test
    void siEncajanVariosONingunoNoTocaNada() throws Exception {
        MemoriaLargoPlazo m = memoria("# Pendiente\n- Comprar pan\n- Comprar leche\n");

        String varios = m.olvidar("comprar");
        String ninguno = m.corregir("huevos", "Comprar huevos");

        assertTrue(varios.contains("encajan varios"), varios);
        assertTrue(ninguno.contains("no encuentro"), ninguno);
        assertEquals("# Pendiente\n- Comprar pan\n- Comprar leche\n", fichero());
    }

    @Test
    void enElPromptNoSalenLasSeccionesVacias() throws Exception {
        MemoriaLargoPlazo m = memoria("# Daniel\n\n# Leo\n- Leo tiene 7 años.\n\n# Casa\n");
        String contenido = m.contenido();
        assertEquals("# Leo\n- Leo tiene 7 años.", contenido);
        assertFalse(contenido.contains("Casa"));
    }
}
