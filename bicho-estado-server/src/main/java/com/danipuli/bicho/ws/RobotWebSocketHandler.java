package com.danipuli.bicho.ws;

import com.danipuli.bicho.model.Estado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
 * ESP32 → servidor, cuando hablas (botón pulsado, o voz oída en escucha continua):
 *   texto   { "tipo": "inicio_audio", "modo": "boton" | "escucha" }
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
 *   texto   { "tipo": "ignorado" }                  ← lo oído no iba para el robot (sin su nombre)
 */
@Component
public class RobotWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RobotWebSocketHandler.class);

    /** Grabación máxima que aceptamos de la ESP32: 30 s a 16 kHz y 16 bits. */
    private static final int MAX_BYTES_GRABACION = 30 * 16_000 * 2;

    /** Tamaño de cada trozo de audio que mandamos a la ESP32. */
    private static final int BYTES_POR_TROZO = 4096;

    /** Cuánto audio mandamos por delante de lo que ya está sonando en la ESP32. */
    private static final long VENTAJA_AUDIO_MS = 1000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sesiones = new CopyOnWriteArraySet<>();
    private final Map<String, ByteArrayOutputStream> grabaciones = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventos;
    private final EstadoWebSocketHandler estado;

    private final Map<String, Boolean> modosEscucha = new ConcurrentHashMap<>();
    /** Por sesión: ms desde la última respuesta cuando la ESP32 empezó a grabar. */
    private final Map<String, Long> respuestaAlEmpezar = new ConcurrentHashMap<>();

    /** Volumen del altavoz de la ESP32 (0..100), o -1 si aún no lo sabemos. */
    private volatile int volumen = -1;

    /** Cuándo terminó de hablar el robot por última vez (para seguir sin decir su nombre). */
    private volatile long ultimaRespuestaFin = 0;

    /** true si la respuesta en curso es una despedida (al acabarla no se sigue escuchando). */
    private volatile boolean cerrarAlAcabar = false;

    /**
     * Cola de movimientos de las ruedas: Claude pide todos los pasos de golpe (p. ej. un baile),
     * y aquí se mandan a la ESP32 uno detrás de otro, cada uno cuando acaba el anterior.
     */
    private final ScheduledExecutorService colaRuedas = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ruedas");
        t.setDaemon(true);
        return t;
    });
    private final List<ScheduledFuture<?>> movimientosPendientes = new ArrayList<>();
    /** Cuándo acaba el último movimiento en cola (0 = ruedas paradas). */
    private long finMovimientos = 0;

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
        modosEscucha.remove(session.getId());
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
                // "boton": has pulsado BOOT (siempre va para el robot)
                // "escucha": la ESP32 ha oído voz ella sola (solo cuenta si dices su nombre)
                boolean escucha = "escucha".equals(json.path("modo").asText("boton"));
                modosEscucha.put(session.getId(), escucha);
                // La ventana para seguir sin decir el nombre se mide al EMPEZAR a hablar, no cuando
                // acaba la transcripción (que puede tardar varios segundos)
                respuestaAlEmpezar.put(session.getId(), msDesdeUltimaRespuesta());
                if (!escucha) {
                    estado.cambiarEstado(Estado.ESCUCHANDO, "");
                }
            }
            case "fin_audio" -> {
                ByteArrayOutputStream grabacion = grabaciones.remove(session.getId());
                boolean escucha = Boolean.TRUE.equals(modosEscucha.remove(session.getId()));
                Long msDesdeRespuesta = respuestaAlEmpezar.remove(session.getId());
                if (grabacion == null || grabacion.size() == 0) {
                    if (!escucha) {
                        estado.cambiarEstado(Estado.REPOSO, "");
                    }
                    enviarIgnorado(session);
                    return;
                }
                log.info("Audio recibido de la ESP32 ({}): {} bytes (~{} s)", escucha ? "escucha" : "botón",
                        grabacion.size(), grabacion.size() / 32_000.0);
                eventos.publishEvent(new AudioEsp32Recibido(session, grabacion.toByteArray(), escucha,
                        msDesdeRespuesta == null ? Long.MAX_VALUE : msDesdeRespuesta));
            }
            case "reproduccion_fin" -> {
                if (cerrarAlAcabar) {
                    // Se ha despedido: hasta que no le llamen por su nombre, no escucha
                    cerrarAlAcabar = false;
                    ultimaRespuestaFin = 0;
                    log.info("La ESP32 ha terminado de hablar y se ha despedido: ahora solo responde a su nombre");
                } else {
                    ultimaRespuestaFin = System.currentTimeMillis();
                    log.info("La ESP32 ha terminado de hablar: se le puede seguir hablando sin decir su nombre");
                }
                estado.cambiarEstado(Estado.REPOSO, "");
            }
            case "hola" -> {
                // La ESP32 se presenta al conectar y dice su volumen guardado
                volumen = json.path("volumen").asInt(-1);
                log.info("La ESP32 dice hola (volumen {})", volumen);
                // Solo viene la primera vez tras encenderse: por qué se reinició la placa
                String reinicio = json.path("reinicio").asText("");
                switch (reinicio) {
                    case "" -> { }
                    case "brownout" -> log.warn("La ESP32 se ha reiniciado por FALTA DE CORRIENTE (brownout): "
                            + "los servos o el altavoz piden más de lo que da la alimentación");
                    case "watchdog", "fallo_programa" ->
                            log.warn("La ESP32 se ha reiniciado por un fallo del programa ({})", reinicio);
                    default -> log.info("La ESP32 se ha encendido (motivo: {})", reinicio);
                }
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
     * Pone un movimiento de las ruedas en cola, detrás de los que ya haya.
     *
     * @return milisegundos que faltan para que empiece
     */
    public synchronized long encolarMovimiento(Map<String, Object> comando, long duracionMs) {
        long ahora = System.currentTimeMillis();
        long inicio = Math.max(ahora, finMovimientos);
        finMovimientos = inicio + duracionMs;
        movimientosPendientes.removeIf(ScheduledFuture::isDone);
        movimientosPendientes.add(colaRuedas.schedule(() -> enviarComando(comando), inicio - ahora,
                TimeUnit.MILLISECONDS));
        return inicio - ahora;
    }

    /** Anula los movimientos pendientes y, si las ruedas se están moviendo, las para ya. */
    public synchronized void cancelarMovimientos() {
        movimientosPendientes.forEach(f -> f.cancel(false));
        movimientosPendientes.clear();
        if (finMovimientos > System.currentTimeMillis()) {
            enviarComando(Map.of("cmd", "ruedas", "accion", "parar", "velocidad", 0, "duracion_ms", 0));
        }
        finMovimientos = 0;
    }

    @PreDestroy
    public void alParar() {
        colaRuedas.shutdownNow();
    }

    /**
     * Manda la respuesta hablada (PCM 16 bits mono) a una ESP32 concreta, en trozos, al ritmo
     * al que se reproduce (con un poco de ventaja). Si se manda todo de golpe, se queda en cola
     * por el camino y los mensajes de control (los ping/pong del heartbeat, comandos de ruedas)
     * llegan con muchos segundos de retraso, y la ESP32 acaba dando la conexión por muerta.
     */
    public void enviarAudio(WebSocketSession sesion, byte[] pcm, int sampleRate) {
        enviar(sesion, new TextMessage(aJson(Map.of("tipo", "audio_inicio", "sample_rate", sampleRate))));
        long inicio = System.currentTimeMillis();
        double bytesPorMs = sampleRate * 2 / 1000.0;
        for (int i = 0; i < pcm.length && sesion.isOpen(); i += BYTES_POR_TROZO) {
            // No ir más de VENTAJA_AUDIO_MS por delante de lo que ya ha sonado
            long msDeAudioEnviado = (long) (i / bytesPorMs);
            long espera = msDeAudioEnviado - VENTAJA_AUDIO_MS - (System.currentTimeMillis() - inicio);
            if (espera > 0) {
                try {
                    Thread.sleep(espera);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            int fin = Math.min(pcm.length, i + BYTES_POR_TROZO);
            enviar(sesion, new BinaryMessage(ByteBuffer.wrap(pcm, i, fin - i)));
        }
        enviar(sesion, new TextMessage(aJson(Map.of("tipo", "audio_fin"))));
    }

    /** Avisa a una ESP32 de que su turno ha fallado (para que vuelva a esperar). */
    public void enviarError(WebSocketSession sesion, String mensaje) {
        enviar(sesion, new TextMessage(aJson(Map.of("tipo", "error", "mensaje", mensaje))));
    }

    /** Avisa a una ESP32 de que lo que ha oído no iba para el robot (vuelve a escuchar). */
    public void enviarIgnorado(WebSocketSession sesion) {
        enviar(sesion, new TextMessage(aJson(Map.of("tipo", "ignorado"))));
    }

    /**
     * Cierra la conversación: cuando termine de decir la respuesta actual, ya no escuchará
     * lo que se diga hasta que alguien diga su nombre.
     */
    public void terminarConversacion() {
        cerrarAlAcabar = true;
        ultimaRespuestaFin = 0;
    }

    /** Milisegundos desde que el robot terminó de hablar por última vez. */
    public long msDesdeUltimaRespuesta() {
        return ultimaRespuestaFin == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - ultimaRespuestaFin;
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

    /**
     * Evento: una ESP32 ha terminado de grabar lo que le has dicho.
     *
     * @param escucha          true si lo grabó ella sola al oír voz (hay que comprobar que dices su
     *                         nombre); false si has pulsado el botón
     * @param msDesdeRespuesta ms que llevaba callado el robot cuando empezaste a hablar
     *                         (Long.MAX_VALUE si no venía de una respuesta, o si se despidió)
     */
    public record AudioEsp32Recibido(WebSocketSession sesion, byte[] pcm16k, boolean escucha,
                                     long msDesdeRespuesta) {
    }
}
