package com.danipuli.bicho.ws;

import com.danipuli.bicho.model.Estado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Handler del WebSocket en /ws/robot: el canal entre el servidor y el cuerpo del bicho.
 *
 * Se conectan dos tipos de clientes:
 *   - la ESP32:          ws://servidor:8080/ws/robot?cliente=esp32
 *   - la web de pruebas: ws://servidor:8080/ws/robot?cliente=web  (solo para ver los comandos)
 *
 * Servidor → todos (texto JSON), los comandos físicos:
 *   { "cmd": "ruedas", "accion": "adelante", "velocidad": 60, "duracion_ms": 1000 }
 *
 * ESP32 → servidor, cuando hablas (botón pulsado):
 *   texto   { "tipo": "inicio_audio" }
 *   binario PCM 16 bits, mono, 16 kHz, little-endian (varios trozos)
 *   texto   { "tipo": "fin_audio" }
 *   texto   { "tipo": "reproduccion_fin" }     ← cuando termina de decir la respuesta
 *   texto   { "tipo": "hola", "volumen": 70 }  ← al conectar
 *
 * Servidor → ESP32, otros comandos:
 *   { "cmd": "volumen", "valor": 80 }
 *
 * Servidor → esa ESP32, la respuesta hablada:
 *   texto   { "tipo": "audio_inicio", "sample_rate": 24000 }
 *   binario PCM 16 bits, mono, 24 kHz (varios trozos)
 *   texto   { "tipo": "audio_fin" }
 *   texto   { "tipo": "error", "mensaje": "..." }   ← si algo falla (no hay audio)
 */
@Component
public class RobotWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RobotWebSocketHandler.class);

    /** Grabación máxima que aceptamos de la ESP32: 30 s a 16 kHz y 16 bits. */
    private static final int MAX_BYTES_GRABACION = 30 * 16_000 * 2;

    /** Tamaño de cada trozo de audio que mandamos a la ESP32. */
    private static final int BYTES_POR_TROZO = 4096;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sesiones = new CopyOnWriteArraySet<>();
    private final Map<String, ByteArrayOutputStream> grabaciones = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventos;
    private final EstadoWebSocketHandler estado;

    /** Volumen del altavoz de la ESP32 (0..100), o -1 si aún no lo sabemos. */
    private volatile int volumen = -1;

    public RobotWebSocketHandler(ApplicationEventPublisher eventos, EstadoWebSocketHandler estado) {
        this.eventos = eventos;
        this.estado = estado;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sesiones.add(session);
        log.info("Conectado a /ws/robot: {} ({})", session.getId(), esEsp32(session) ? "ESP32" : "web");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sesiones.remove(session);
        grabaciones.remove(session.getId());
        log.info("Desconectado de /ws/robot: {} ({}) — motivo: {} {}", session.getId(),
                esEsp32(session) ? "ESP32" : "web", status.getCode(),
                status.getReason() == null ? "" : status.getReason());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Error de conexión en /ws/robot {}: {}", session.getId(), exception.toString());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode json;
        try {
            json = objectMapper.readTree(message.getPayload());
        } catch (IOException ex) {
            log.warn("Mensaje de /ws/robot que no es JSON: {}", message.getPayload());
            return;
        }
        switch (json.path("tipo").asText("")) {
            case "inicio_audio" -> {
                grabaciones.put(session.getId(), new ByteArrayOutputStream());
                estado.cambiarEstado(Estado.ESCUCHANDO, "");
            }
            case "fin_audio" -> {
                ByteArrayOutputStream grabacion = grabaciones.remove(session.getId());
                if (grabacion == null || grabacion.size() == 0) {
                    estado.cambiarEstado(Estado.REPOSO, "");
                    return;
                }
                log.info("Audio recibido de la ESP32: {} bytes (~{} s)",
                        grabacion.size(), grabacion.size() / 32_000.0);
                eventos.publishEvent(new AudioEsp32Recibido(session, grabacion.toByteArray()));
            }
            case "reproduccion_fin" -> estado.cambiarEstado(Estado.REPOSO, "");
            case "hola" -> {
                // La ESP32 se presenta al conectar y dice su volumen guardado
                volumen = json.path("volumen").asInt(-1);
                log.info("La ESP32 dice hola (volumen {})", volumen);
            }
            default -> log.info("Mensaje de /ws/robot sin tipo conocido: {}", message.getPayload());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ByteArrayOutputStream grabacion = grabaciones.get(session.getId());
        if (grabacion == null) {
            return; // audio fuera de un inicio_audio/fin_audio: se ignora
        }
        if (grabacion.size() > MAX_BYTES_GRABACION) {
            return; // demasiado largo: nos quedamos con los primeros 30 s
        }
        ByteBuffer datos = message.getPayload();
        byte[] trozo = new byte[datos.remaining()];
        datos.get(trozo);
        grabacion.writeBytes(trozo);
    }

    /**
     * Manda un comando a todos los conectados (ESP32 y webs de pruebas).
     */
    public void enviarComando(Map<String, Object> comando) {
        String json = aJson(comando);
        if (json == null) {
            return;
        }
        log.info("Comando robot: {}", json);
        for (WebSocketSession sesion : sesiones) {
            enviar(sesion, new TextMessage(json));
        }
    }

    /**
     * Manda la respuesta hablada (PCM 16 bits mono) a una ESP32 concreta, en trozos.
     */
    public void enviarAudio(WebSocketSession sesion, byte[] pcm, int sampleRate) {
        enviar(sesion, new TextMessage(aJson(Map.of("tipo", "audio_inicio", "sample_rate", sampleRate))));
        for (int i = 0; i < pcm.length; i += BYTES_POR_TROZO) {
            int fin = Math.min(pcm.length, i + BYTES_POR_TROZO);
            enviar(sesion, new BinaryMessage(ByteBuffer.wrap(pcm, i, fin - i)));
        }
        enviar(sesion, new TextMessage(aJson(Map.of("tipo", "audio_fin"))));
    }

    /** Avisa a una ESP32 de que su turno ha fallado (para que vuelva a esperar). */
    public void enviarError(WebSocketSession sesion, String mensaje) {
        enviar(sesion, new TextMessage(aJson(Map.of("tipo", "error", "mensaje", mensaje))));
    }

    /** Cambia el volumen del altavoz de la ESP32 (ella lo guarda para la próxima vez). */
    public void cambiarVolumen(int nuevo) {
        volumen = nuevo;
        enviarComando(Map.of("cmd", "volumen", "valor", nuevo));
    }

    public int getVolumen() {
        return volumen;
    }

    /** true si hay al menos una ESP32 conectada (no cuenta las webs de pruebas). */
    public boolean hayRobotConectado() {
        return sesiones.stream().anyMatch(s -> s.isOpen() && esEsp32(s));
    }

    private void enviar(WebSocketSession sesion, WebSocketMessage<?> mensaje) {
        if (!sesion.isOpen()) {
            return;
        }
        try {
            // Varios hilos pueden mandar a la vez (comandos de ruedas y audio): de uno en uno
            synchronized (sesion) {
                sesion.sendMessage(mensaje);
            }
        } catch (IOException ex) {
            log.warn("No se pudo enviar a la sesión {}", sesion.getId(), ex);
        }
    }

    private String aJson(Object objeto) {
        try {
            return objectMapper.writeValueAsString(objeto);
        } catch (IOException ex) {
            log.warn("No se pudo convertir a JSON: {}", objeto, ex);
            return null;
        }
    }

    private boolean esEsp32(WebSocketSession sesion) {
        return sesion.getUri() != null
                && sesion.getUri().getQuery() != null
                && sesion.getUri().getQuery().contains("cliente=esp32");
    }

    /** Evento: una ESP32 ha terminado de grabar lo que le has dicho. */
    public record AudioEsp32Recibido(WebSocketSession sesion, byte[] pcm16k) {
    }
}
