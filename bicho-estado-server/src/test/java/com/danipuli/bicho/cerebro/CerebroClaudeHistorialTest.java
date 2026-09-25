package com.danipuli.bicho.cerebro;

import com.anthropic.models.messages.MessageParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El recorte del historial solo debe cortar al principio de un turno (lo que dijo el
 * usuario), también cuando hay turnos muy largos llenos de herramientas (un baile).
 */
class CerebroClaudeHistorialTest {

    @TempDir
    Path carpeta;

    @Test
    void recortaPorElPrincipioDeUnTurno() throws Exception {
        CerebroClaude cerebro = cerebroDePrueba();
        List<MessageParam> mensajes = new ArrayList<>();
        List<MessageParam> inicios = new ArrayList<>();
        // 12 turnos: pregunta + 2 idas y vueltas de herramientas + respuesta = 4 mensajes cada uno
        for (int t = 0; t < 12; t++) {
            MessageParam pregunta = invocar(cerebro, "mensajeDeUsuario", "pregunta " + t);
            inicios.add(pregunta);
            mensajes.add(pregunta);
            mensajes.add(asistente("herramienta " + t));
            mensajes.add(usuarioResultado("resultado " + t));
            mensajes.add(asistente("respuesta " + t));
        }

        invocar(cerebro, "guardarHistorial", mensajes);
        List<MessageParam> historial = historial(cerebro);

        assertTrue(historial.size() <= 30, "no pasa del máximo: " + historial.size());
        assertTrue(inicios.contains(historial.get(0)), "empieza en una pregunta del usuario");
        assertSame(mensajes.get(mensajes.size() - 1), historial.get(historial.size() - 1));
    }

    @Test
    void unTurnoLarguisimoSeGuardaEntero() throws Exception {
        CerebroClaude cerebro = cerebroDePrueba();
        List<MessageParam> mensajes = new ArrayList<>();
        MessageParam pregunta = invocar(cerebro, "mensajeDeUsuario", "baila");
        mensajes.add(pregunta);
        for (int i = 0; i < 40; i++) {
            mensajes.add(asistente("paso " + i));
            mensajes.add(usuarioResultado("hecho " + i));
        }
        mensajes.add(asistente("¡ya está!"));

        invocar(cerebro, "guardarHistorial", mensajes);

        List<MessageParam> historial = historial(cerebro);
        assertEquals(mensajes.size(), historial.size());
        assertSame(pregunta, historial.get(0));
    }

    // ------------------------------------------------------------------ utilidades

    private CerebroClaude cerebroDePrueba() {
        return new CerebroClaude("", "", "claude-sonnet-5",
                carpeta.resolve("personalidad.txt").toString(),
                carpeta.resolve("memoria.txt").toString(),
                carpeta.resolve("conversacion.json").toString(),
                "Atlantic/Canary", "", 3, 3000, 70, 20, null, null);
    }

    private static MessageParam asistente(String texto) {
        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(texto).build();
    }

    private static MessageParam usuarioResultado(String texto) {
        return MessageParam.builder().role(MessageParam.Role.USER).content(texto).build();
    }

    @SuppressWarnings("unchecked")
    private static <T> T invocar(CerebroClaude cerebro, String metodo, Object argumento) throws Exception {
        Class<?> tipo = argumento instanceof String ? String.class : List.class;
        Method m = CerebroClaude.class.getDeclaredMethod(metodo, tipo);
        m.setAccessible(true);
        return (T) m.invoke(cerebro, argumento);
    }

    @SuppressWarnings("unchecked")
    private static List<MessageParam> historial(CerebroClaude cerebro) throws Exception {
        Field f = CerebroClaude.class.getDeclaredField("historial");
        f.setAccessible(true);
        return (List<MessageParam>) f.get(cerebro);
    }
}
