package com.danipuli.bicho.cerebro;

import com.danipuli.bicho.voz.OpenAiVozClient;
import com.danipuli.bicho.ws.RobotWebSocketHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Atiende lo que te oye la ESP32: cuando termina de grabar, hace el turno completo
 * (STT → Claude → TTS) en segundo plano y le devuelve la respuesta hablada.
 */
@Component
public class ConversacionEsp32 {

    private static final Logger log = LoggerFactory.getLogger(ConversacionEsp32.class);

    /** Formato de lo que graba la ESP32. */
    private static final int SAMPLE_RATE_MICRO = 16_000;

    /** OpenAI devuelve el formato "pcm" siempre a 24 kHz, 16 bits, mono. */
    private static final int SAMPLE_RATE_VOZ = 24_000;

    // Un turno detrás de otro (solo hay un bicho y una conversación)
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "conversacion-esp32");
        t.setDaemon(true);
        return t;
    });

    private final AsistenteService asistente;
    private final RobotWebSocketHandler robot;
    private final OpenAiVozClient voz;
    private final DetectorNombre detectorNombre;
    private final long ventanaMs;
    /** La pista de la transcripción, normalizada, para reconocer cuando la devuelve tal cual. */
    private final String pistaStt;

    public ConversacionEsp32(AsistenteService asistente, RobotWebSocketHandler robot, OpenAiVozClient voz,
                             DetectorNombre detectorNombre,
                             @Value("${bicho.activacion.ventana-segundos:8}") long ventanaSegundos,
                             @Value("${bicho.openai.pista-stt:}") String pistaStt) {
        this.asistente = asistente;
        this.robot = robot;
        this.voz = voz;
        this.detectorNombre = detectorNombre;
        this.ventanaMs = ventanaSegundos * 1000;
        this.pistaStt = DetectorNombre.normalizar(pistaStt);
    }

    @EventListener
    public void alRecibirAudio(RobotWebSocketHandler.AudioEsp32Recibido evento) {
        hilo.submit(() -> atender(evento));
    }

    private void atender(RobotWebSocketHandler.AudioEsp32Recibido evento) {
        byte[] wav = aWav(evento.pcm16k(), SAMPLE_RATE_MICRO);
        diagnosticar(evento.pcm16k(), wav);
        AsistenteService.Resultado r = evento.escucha()
                ? turnoDeEscucha(evento, wav)
                : asistente.turnoDeVoz(wav, "voz.wav", "pcm");
        if (r == null) {
            return; // no iba para el robot: ya se ha avisado a la ESP32
        }
        if (r.error() != null) {
            log.info("Turno de la ESP32 fallido: {}", r.error());
            robot.enviarError(evento.sesion(), r.error());
            return;
        }
        log.info("ESP32 — tú: '{}' → bicho: '{}'", r.tuTexto(), r.texto());
        if (r.audio() == null) {
            robot.enviarError(evento.sesion(), r.avisoVoz() != null ? r.avisoVoz() : "Sin respuesta hablada");
            return;
        }
        robot.enviarAudio(evento.sesion(), r.audio(), SAMPLE_RATE_VOZ);
    }

    /**
     * Voz que la ESP32 ha oído ella sola: solo se contesta si dices el nombre del robot,
     * o si acaba de hablar (para poder seguir la conversación sin repetirlo).
     *
     * @return el resultado del turno, o null si no iba para el robot
     */
    private AsistenteService.Resultado turnoDeEscucha(RobotWebSocketHandler.AudioEsp32Recibido evento, byte[] wav) {
        String texto;
        try {
            texto = voz.transcribir(wav, "voz.wav");
        } catch (Exception ex) {
            log.warn("Fallo al transcribir la escucha: {}", ex.getMessage());
            robot.enviarIgnorado(evento.sesion());
            return null;
        }
        if (esEcoDeLaPista(texto)) {
            log.info("La transcripción solo repite la pista (no se entendía el audio), lo ignoro: '{}'", texto);
            robot.enviarIgnorado(evento.sesion());
            return null;
        }
        boolean conNombre = detectorNombre.contieneNombre(texto);
        boolean enConversacion = robot.msDesdeUltimaRespuesta() < ventanaMs;
        if (texto.isBlank() || (!conNombre && !enConversacion)) {
            log.info("Oído sin nombre, lo ignoro: '{}'", texto);
            robot.enviarIgnorado(evento.sesion());
            return null;
        }
        log.info("Oído {}: '{}'", conNombre ? "con su nombre" : "siguiendo la conversación", texto);
        return asistente.turnoDeTexto(texto, "pcm");
    }

    /**
     * Cuando el audio no se entiende (ruido, un golpe...), la transcripción a veces devuelve la
     * propia pista que le mandamos ("Hablando con RoboDragón..."), y como lleva el nombre, el robot
     * se despertaría solo. Es eco si todo lo transcrito está dentro de la pista y es más de la
     * mitad de ella (así un "RoboDragón" a secas sí cuenta).
     */
    boolean esEcoDeLaPista(String texto) {
        String t = DetectorNombre.normalizar(texto);
        return !t.isEmpty() && !pistaStt.isEmpty() && pistaStt.contains(t) && t.length() * 2 > pistaStt.length();
    }

    /**
     * Para saber si el micro de la ESP32 oye bien: apunta en el log la duración y el nivel de
     * la grabación, y la guarda en ultimo-audio-esp32.wav (en la carpeta del proyecto) para
     * poder escucharla.
     */
    private void diagnosticar(byte[] pcm, byte[] wav) {
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int muestras = pcm.length / 2;
        int pico = 0;
        double suma = 0;
        for (int i = 0; i < muestras; i++) {
            int s = b.getShort();
            pico = Math.max(pico, Math.abs(s));
            suma += (double) s * s;
        }
        double rms = muestras == 0 ? 0 : Math.sqrt(suma / muestras);
        String valoracion = pico == 0 ? "SILENCIO TOTAL (¿micro desconectado?)"
                : pico < 300 ? "muy bajo (sube el volumen del micro)"
                : pico >= 32000 ? "saturado (baja el volumen del micro)"
                : "ok";
        log.info("Audio ESP32: {} s, pico {} de 32767, nivel medio {} → {}",
                String.format("%.1f", muestras / (double) SAMPLE_RATE_MICRO), pico, Math.round(rms), valoracion);
        try {
            Files.write(Path.of("ultimo-audio-esp32.wav"), wav);
        } catch (IOException ex) {
            log.warn("No se pudo guardar ultimo-audio-esp32.wav: {}", ex.getMessage());
        }
    }

    /** Pone la cabecera WAV delante del PCM crudo, que es lo que entiende la API de transcripción. */
    static byte[] aWav(byte[] pcm, int sampleRate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16)
                .putShort((short) 1)            // PCM
                .putShort((short) 1)            // mono
                .putInt(sampleRate)
                .putInt(sampleRate * 2)         // bytes por segundo
                .putShort((short) 2)            // bytes por muestra
                .putShort((short) 16);          // bits por muestra
        b.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return b.array();
    }

    @PreDestroy
    public void alParar() {
        hilo.shutdownNow();
    }
}
