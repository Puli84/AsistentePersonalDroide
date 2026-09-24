package com.danipuli.bicho.ws;

import com.danipuli.bicho.model.Estado;
import com.danipuli.bicho.model.EstadoMensaje;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handler del WebSocket en /ws/estado.
 *
 * Cualquiera que se conecte aquí (la web de la cara, un cliente de pruebas, o el día de
 * mañana la propia ESP32 si deja de usar el firmware XiaoZhi) puede:
 *   - recibir el estado actual nada más conectarse
 *   - recibir cada cambio de estado que se retransmite a todos los conectados
 *   - mandar un mensaje de texto con un JSON tipo {"estado":"hablando","texto":"..."} para
 *     cambiar el estado manualmente (útil para probar sin la ESP32 todavía)
 *
 * IMPORTANTE: el agente de xiaozhi.me solo llama a mostrar_cara UNA VEZ por turno (con
 * estado="hablando") — llamarla una segunda vez con "reposo" en el mismo turno hacía que
 * el pipeline de voz repitiera la respuesta dos veces. Así que aquí, cuando entra un
 * "hablando", programamos una vuelta automática a "reposo" — el tiempo de espera se
 * calcula a partir de la longitud del texto recibido (aproximando cuánto puede tardar en
 * decirlo en voz), y si no hay texto, se usa un tiempo fijo de respaldo.
 */
@Component
public class EstadoWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EstadoWebSocketHandler.class);

    /** Tiempo de respaldo si "hablando" llega sin texto (no podemos estimar duración). */
    private static final long MS_AUTO_REPOSO_SIN_TEXTO = 6_000;

    /** Límites del cálculo por longitud de texto, para no pasarnos en ningún extremo. */
    private static final long MS_AUTO_REPOSO_MINIMO = 2_500;
    private static final long MS_AUTO_REPOSO_MAXIMO = 20_000;

    /** Milisegundos estimados que se tarda en decir cada carácter (aprox. voz normal). */
    private static final long MS_POR_CARACTER = 65;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sesiones = new CopyOnWriteArraySet<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread hilo = new Thread(r, "auto-reposo");
        hilo.setDaemon(true);
        return hilo;
    });

    private volatile Estado estadoActual = Estado.REPOSO;
    private volatile String textoActual = "";

    // Sirve para poder ignorar un auto-reposo programado si mientras tanto llega un
    // cambio de estado más reciente.
    private final AtomicLong version = new AtomicLong(0);
    private volatile ScheduledFuture<?> autoReposoProgramado;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sesiones.add(session);
        log.info("Cliente conectado al WebSocket de estado: {} (total: {})", session.getId(), sesiones.size());
        enviarASesion(session, EstadoMensaje.de(estadoActual, textoActual));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sesiones.remove(session);
        log.info("Cliente desconectado del WebSocket de estado: {} (total: {})", session.getId(), sesiones.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            EstadoMensaje entrante = objectMapper.readValue(message.getPayload(), EstadoMensaje.class);
            cambiarEstado(Estado.desdeTexto(entrante.getEstado()), entrante.getTexto());
        } catch (IOException ex) {
            log.warn("Mensaje de WebSocket que no se pudo interpretar como EstadoMensaje: {}", message.getPayload());
        }
    }

    /**
     * Cambia el estado actual y lo retransmite a todos los clientes conectados
     * (la web de la cara, cualquier otro cliente que esté escuchando).
     * Este es el método que usa también el controlador REST /api/estado y el cliente MCP.
     */
    public void cambiarEstado(Estado nuevoEstado, String texto) {
        long miVersion = version.incrementAndGet();

        this.estadoActual = nuevoEstado;
        this.textoActual = texto == null ? "" : texto;

        EstadoMensaje mensaje = EstadoMensaje.de(estadoActual, textoActual);
        log.info("Nuevo estado: {} ({} clientes, texto='{}')", estadoActual, sesiones.size(), textoActual);

        for (WebSocketSession sesion : sesiones) {
            enviarASesion(sesion, mensaje);
        }

        if (autoReposoProgramado != null) {
            autoReposoProgramado.cancel(false);
            autoReposoProgramado = null;
        }

        if (nuevoEstado == Estado.HABLANDO) {
            long duracionMs = calcularDuracionMs(textoActual);
            log.info("Auto-reposo programado en {} ms", duracionMs);

            autoReposoProgramado = scheduler.schedule(() -> {
                // Si mientras esperábamos ya ha llegado otro cambio de estado, no hacemos nada.
                if (version.get() == miVersion) {
                    log.info("Pasado el tiempo estimado tras 'hablando' — vuelvo solo a 'reposo'");
                    cambiarEstado(Estado.REPOSO, "");
                }
            }, duracionMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Estima cuánto va a tardar en decirse un texto, para dejar la cara en "hablando"
     * el tiempo justo. Sin texto, usa un valor fijo de respaldo.
     */
    private long calcularDuracionMs(String texto) {
        if (texto == null || texto.isBlank()) {
            return MS_AUTO_REPOSO_SIN_TEXTO;
        }
        long estimado = (long) texto.length() * MS_POR_CARACTER;
        return Math.max(MS_AUTO_REPOSO_MINIMO, Math.min(MS_AUTO_REPOSO_MAXIMO, estimado));
    }

    public Estado getEstadoActual() {
        return estadoActual;
    }

    public String getTextoActual() {
        return textoActual;
    }

    private void enviarASesion(WebSocketSession sesion, EstadoMensaje mensaje) {
        if (!sesion.isOpen()) {
            return;
        }
        try {
            sesion.sendMessage(new TextMessage(objectMapper.writeValueAsString(mensaje)));
        } catch (IOException ex) {
            log.warn("No se pudo enviar el estado a la sesión {}", sesion.getId(), ex);
        }
    }

    @PreDestroy
    public void alParar() {
        scheduler.shutdownNow();
    }
}