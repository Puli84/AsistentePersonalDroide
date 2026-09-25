package com.danipuli.bicho.voz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * La "voz" del bicho: usa la API de OpenAI para
 *   - STT: pasar lo que dices (audio) a texto
 *   - TTS: pasar la respuesta de Claude (texto) a audio
 *
 * Se llama por HTTP directamente (java.net.http), sin SDK, porque son solo dos endpoints.
 */
@Component
public class OpenAiVozClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiVozClient.class);

    private static final String URL_TRANSCRIPCION = "https://api.openai.com/v1/audio/transcriptions";
    private static final String URL_VOZ = "https://api.openai.com/v1/audio/speech";

    /** Cómo queremos que suene el bicho (solo lo usan los modelos gpt-4o-*-tts). */
    private static final String INSTRUCCIONES_VOZ =
            "Habla en español de España, con tono cercano, alegre y un poco travieso, como un robot mascota.";

    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * Cuánto esperamos a OpenAI en cada intento (petición entera). Normalmente tarda 1-3 s, pero
     * a veces se atasca más de un minuto: es mejor reintentar que dejar a la ESP32 esperando
     * (ella se rinde a los 30 s, y dos intentos de 10 s más Claude caben en ese tiempo).
     */
    private static final Duration TIMEOUT_PETICION = Duration.ofSeconds(10);

    /** Tamaño de cada trozo de texto al generar la voz (unos 20 s de voz como mucho). */
    static final int MAX_CARACTERES_TROZO = 300;

    /** Para pedir a la vez la voz de los trozos de un texto largo. */
    private final ExecutorService hilosVoz = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "voz-trozos");
        t.setDaemon(true);
        return t;
    });

    // HTTP/1.1: con HTTP/2 a veces se reutiliza una conexión muerta y la petición se queda colgada
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey;
    private final String modeloStt;
    private final String modeloTts;
    private final String voz;
    private final String pistaStt;

    public OpenAiVozClient(@Value("${bicho.openai.api-key:}") String apiKey,
                           @Value("${bicho.openai.modelo-stt:gpt-4o-mini-transcribe}") String modeloStt,
                           @Value("${bicho.openai.modelo-tts:gpt-4o-mini-tts}") String modeloTts,
                           @Value("${bicho.openai.voz:coral}") String voz,
                           @Value("${bicho.openai.pista-stt:}") String pistaStt) {
        this.apiKey = apiKey;
        this.modeloStt = modeloStt;
        this.modeloTts = modeloTts;
        this.voz = voz;
        this.pistaStt = pistaStt;
    }

    public boolean configurado() {
        return apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("PON_AQUI");
    }

    /**
     * Audio → texto. Acepta los formatos que manda el navegador (webm/ogg) y WAV (el que mandará la ESP32).
     */
    public String transcribir(byte[] audio, String nombreFichero) throws IOException, InterruptedException {
        comprobarConfigurado();
        String frontera = "----bicho" + UUID.randomUUID();
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        escribirCampo(cuerpo, frontera, "model", modeloStt);
        escribirCampo(cuerpo, frontera, "language", "es");
        if (pistaStt != null && !pistaStt.isBlank()) {
            // Palabras que debe escribir bien (por ejemplo el nombre del robot)
            escribirCampo(cuerpo, frontera, "prompt", pistaStt);
        }
        cuerpo.write(("--" + frontera + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + nombreFichero + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        cuerpo.write(audio);
        cuerpo.write(("\r\n--" + frontera + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest peticion = HttpRequest.newBuilder(URI.create(URL_TRANSCRIPCION))
                .timeout(TIMEOUT_PETICION)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + frontera)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()))
                .build();
        HttpResponse<String> respuesta = enviar(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (respuesta.statusCode() != 200) {
            throw new IOException("OpenAI STT respondió " + respuesta.statusCode() + ": " + respuesta.body());
        }
        JsonNode json = mapper.readTree(respuesta.body());
        return json.path("text").asText("").trim();
    }

    /**
     * Texto → audio.
     *
     * Los textos largos (un cuento) tardan en generarse más que el límite de cada petición, así
     * que se parten en trozos de unas pocas frases que se piden a la vez y luego se juntan: el
     * cuento entero tarda lo mismo que un trozo.
     *
     * @param formato "mp3" para el navegador, "pcm" para la ESP32 (PCM 16 bits, 24 kHz, mono, sin cabecera)
     */
    public byte[] sintetizar(String texto, String formato) throws IOException, InterruptedException {
        comprobarConfigurado();
        List<String> trozos = trocear(texto);
        if (trozos.size() == 1) {
            return sintetizarTrozo(texto, formato);
        }
        log.info("Texto largo: pido la voz en {} trozos a la vez", trozos.size());
        List<Future<byte[]>> pendientes = new ArrayList<>();
        for (String trozo : trozos) {
            pendientes.add(hilosVoz.submit(() -> sintetizarTrozo(trozo, formato)));
        }
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        try {
            for (Future<byte[]> pendiente : pendientes) {
                audio.writeBytes(pendiente.get());
            }
        } catch (ExecutionException ex) {
            pendientes.forEach(p -> p.cancel(true));
            if (ex.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(ex.getCause());
        } catch (InterruptedException ex) {
            pendientes.forEach(p -> p.cancel(true));
            throw ex;
        }
        return audio.toByteArray();
    }

    /**
     * Parte el texto por el final de las frases en trozos de como mucho MAX_CARACTERES_TROZO
     * (una frase más larga que eso va sola en su trozo).
     */
    static List<String> trocear(String texto) {
        List<String> trozos = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String frase : texto.strip().split("(?<=[.!?…])\\s+")) {
            if (actual.length() > 0 && actual.length() + 1 + frase.length() > MAX_CARACTERES_TROZO) {
                trozos.add(actual.toString());
                actual.setLength(0);
            }
            if (actual.length() > 0) {
                actual.append(' ');
            }
            actual.append(frase);
        }
        if (actual.length() > 0) {
            trozos.add(actual.toString());
        }
        return trozos;
    }

    private byte[] sintetizarTrozo(String texto, String formato) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(Map.of(
                "model", modeloTts,
                "voice", voz,
                "input", texto,
                "instructions", INSTRUCCIONES_VOZ,
                "response_format", formato));

        HttpRequest peticion = HttpRequest.newBuilder(URI.create(URL_VOZ))
                .timeout(TIMEOUT_PETICION)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> respuesta = enviar(peticion, HttpResponse.BodyHandlers.ofByteArray());
        if (respuesta.statusCode() != 200) {
            throw new IOException("OpenAI TTS respondió " + respuesta.statusCode() + ": "
                    + new String(respuesta.body(), StandardCharsets.UTF_8));
        }
        return respuesta.body();
    }

    /**
     * Manda la petición y, si se cuelga, se corta la conexión o OpenAI dice que está saturado
     * (errores 5xx, p. ej. 503 "Please retry"), lo intenta una vez más.
     */
    private <T> HttpResponse<T> enviar(HttpRequest peticion, HttpResponse.BodyHandler<T> lector)
            throws IOException, InterruptedException {
        HttpResponse<T> respuesta;
        try {
            respuesta = enviarConLimite(peticion, lector);
        } catch (IOException ex) {
            log.warn("OpenAI no ha respondido ({}), reintento", ex.toString());
            return enviarConLimite(peticion, lector);
        }
        if (respuesta.statusCode() >= 500) {
            log.warn("OpenAI ha respondido {}, reintento", respuesta.statusCode());
            return enviarConLimite(peticion, lector);
        }
        return respuesta;
    }

    /**
     * El timeout de HttpRequest solo cuenta hasta que llegan las cabeceras: si OpenAI empieza a
     * responder y luego se atasca a mitad del audio, se quedaría esperando para siempre. Aquí
     * se limita la petición entera, cuerpo incluido.
     */
    private <T> HttpResponse<T> enviarConLimite(HttpRequest peticion, HttpResponse.BodyHandler<T> lector)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> futura = http.sendAsync(peticion, lector);
        try {
            return futura.get(TIMEOUT_PETICION.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            futura.cancel(true);
            throw new IOException("sin respuesta completa en " + TIMEOUT_PETICION.toSeconds() + " s");
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof IOException io ? io : new IOException(ex.getCause());
        }
    }

    private void comprobarConfigurado() {
        if (!configurado()) {
            throw new IllegalStateException(
                    "Falta la API key de OpenAI: ponla en src/main/resources/secrets.properties (bicho.openai.api-key)");
        }
    }

    private static void escribirCampo(ByteArrayOutputStream cuerpo, String frontera, String nombre, String valor)
            throws IOException {
        cuerpo.write(("--" + frontera + "\r\n"
                + "Content-Disposition: form-data; name=\"" + nombre + "\"\r\n\r\n"
                + valor + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
