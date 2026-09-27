package com.danipuli.bicho.n8n;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Las automatizaciones de n8n se leen del fichero y se llaman por su webhook. */
class HerramientasN8nTest {

    @TempDir
    Path carpeta;

    @Test
    void leeLasBuenasYSaltaLasMalDefinidas() throws Exception {
        Path fichero = carpeta.resolve("herramientas-n8n.json");
        Files.writeString(fichero, """
                [
                  {"nombre": "apuntar_nota", "descripcion": "Apunta una nota", "url": "http://localhost:5678/webhook/nota",
                   "parametros": {"texto": "Lo que hay que apuntar"}},
                  {"nombre": "mover_ruedas", "url": "http://x"},
                  {"nombre": "con espacios", "url": "http://x"},
                  {"nombre": "sin_url"}
                ]
                """, StandardCharsets.UTF_8);

        List<HerramientasN8n.Definicion> leidas = new HerramientasN8n(fichero.toString()).leer();

        assertEquals(1, leidas.size());
        assertEquals("apuntar_nota", leidas.get(0).nombre());
        assertEquals(Map.of("texto", "Lo que hay que apuntar"), leidas.get(0).parametros());
        assertEquals("apuntar_nota", HerramientasN8n.aTool(leidas.get(0)).name());
    }

    @Test
    void sinFicheroNoHayHerramientas() {
        assertTrue(new HerramientasN8n(carpeta.resolve("no-existe.json").toString()).leer().isEmpty());
    }

    @Test
    void llamaAlWebhookYDevuelveLaRespuesta() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        HttpServer n8nFalso = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        n8nFalso.createContext("/webhook/prueba", intercambio -> {
            recibido.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] respuesta = "Nota guardada".getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(200, respuesta.length);
            intercambio.getResponseBody().write(respuesta);
            intercambio.close();
        });
        n8nFalso.start();
        try {
            String url = "http://localhost:" + n8nFalso.getAddress().getPort() + "/webhook/prueba";
            HerramientasN8n.Definicion d = new HerramientasN8n.Definicion("apuntar_nota", "", url,
                    Map.of("texto", "t"), false);

            String resultado = new HerramientasN8n("x.json").ejecutar(d, Map.of("texto", "comprar leche"));

            assertEquals("{\"texto\":\"comprar leche\"}", recibido.get());
            assertEquals("Hecho. Respuesta: Nota guardada", resultado);
        } finally {
            n8nFalso.stop(0);
        }
    }

    @Test
    void siN8nNoEstaArrancadoLoDice() {
        HerramientasN8n.Definicion d = new HerramientasN8n.Definicion("apuntar_nota", "",
                "http://localhost:1/webhook/nada", Map.of(), false);
        assertTrue(new HerramientasN8n("x.json").ejecutar(d, Map.of()).contains("no se puede hablar con n8n"));
    }
}
