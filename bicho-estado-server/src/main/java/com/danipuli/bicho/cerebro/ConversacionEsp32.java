package com.danipuli.bicho.cerebro;

import com.danipuli.bicho.ws.RobotWebSocketHandler;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
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

    public ConversacionEsp32(AsistenteService asistente, RobotWebSocketHandler robot) {
        this.asistente = asistente;
        this.robot = robot;
    }

    @EventListener
    public void alRecibirAudio(RobotWebSocketHandler.AudioEsp32Recibido evento) {
        hilo.submit(() -> atender(evento));
    }

    private void atender(RobotWebSocketHandler.AudioEsp32Recibido evento) {
        byte[] wav = aWav(evento.pcm16k(), SAMPLE_RATE_MICRO);
        AsistenteService.Resultado r = asistente.turnoDeVoz(wav, "voz.wav", "pcm");
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
