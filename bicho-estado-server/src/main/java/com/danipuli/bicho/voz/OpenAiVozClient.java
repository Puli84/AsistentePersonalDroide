package com.danipuli.bicho.voz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;

/**
 * La "voz" del bicho: usa la API de OpenAI para
 *   - STT: pasar lo que dices (audio) a texto
 *   - TTS: pasar la respuesta de Claude (texto) a audio
 *
 * Se llama por HTTP directamente (java.net.http), sin SDK, porque son solo dos endpoints.
 */
@Component
public class OpenAiVozClient {

    private static final String URL_TRANSCRIPCION = "https://api.openai.com/v1/audio/transcriptions";
    private static final String URL_VOZ = "https://api.openai.com/v1/audio/speech";

    /** Cómo queremos que suene el bicho (solo lo usan los modelos gpt-4o-*-tts). */
    private static final String INSTRUCCIONES_VOZ =
            "Habla en español de España, con tono cercano, alegre y un poco travieso, como un robot mascota.";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

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
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + frontera)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()))
                .build();
        HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (respuesta.statusCode() != 200) {
            throw new IOException("OpenAI STT respondió " + respuesta.statusCode() + ": " + respuesta.body());
        }
        JsonNode json = mapper.readTree(respuesta.body());
        return json.path("text").asText("").trim();
    }

    /**
     * Texto → audio.
     *
     * @param formato "mp3" para el navegador, "pcm" para la ESP32 (PCM 16 bits, 24 kHz, mono, sin cabecera)
     */
    public byte[] sintetizar(String texto, String formato) throws IOException, InterruptedException {
        comprobarConfigurado();
        String json = mapper.writeValueAsString(Map.of(
                "model", modeloTts,
                "voice", voz,
                "input", texto,
                "instructions", INSTRUCCIONES_VOZ,
                "response_format", formato));

        HttpRequest peticion = HttpRequest.newBuilder(URI.create(URL_VOZ))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofByteArray());
        if (respuesta.statusCode() != 200) {
            throw new IOException("OpenAI TTS respondió " + respuesta.statusCode() + ": "
                    + new String(respuesta.body(), StandardCharsets.UTF_8));
        }
        return respuesta.body();
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
