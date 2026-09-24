package com.danipuli.bicho.mcp;

import com.danipuli.bicho.model.Estado;
import com.danipuli.bicho.ws.EstadoWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Cliente MCP que se conecta al "MCP Endpoint" que xiaozhi.me genera en la consola del
 * agente (pestaña Extensions → Custom Services → MCP Endpoint).
 *
 * xiaozhi.me hace de cliente MCP: es él quien decide, dentro de la conversación, si llama
 * o no a nuestra herramienta "mostrar_cara" — nosotros solo la ofrecemos. Por eso, para que
 * de verdad la llame en los momentos oportunos (al escuchar, al hablar...), hay que
 * decírselo en las instrucciones del agente (pestaña "Role", con "Customize" activado).
 * Sin eso, la herramienta existe pero nadie la usa.
 *
 * Esto no sustituye el estado en tiempo real del pipeline de voz (eso lo lleva el propio
 * firmware XiaoZhi y no es tocable sin recompilar) — es "mejor esfuerzo": el modelo decide
 * cuándo llamarla según el contexto de la conversación.
 */
@Component
public class XiaozhiMcpClient {

    private static final Logger log = LoggerFactory.getLogger(XiaozhiMcpClient.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper = new ObjectMapper();
    private final EstadoWebSocketHandler estadoWebSocketHandler;
    private final String endpointUrl;
    private final boolean habilitado;

    private final StringBuilder bufferEntrante = new StringBuilder();
    private HttpClient httpClient;

    public XiaozhiMcpClient(EstadoWebSocketHandler estadoWebSocketHandler,
                             @Value("${bicho.mcp.endpoint-url:}") String endpointUrl,
                             @Value("${bicho.mcp.enabled:false}") boolean habilitado) {
        this.estadoWebSocketHandler = estadoWebSocketHandler;
        this.endpointUrl = endpointUrl;
        this.habilitado = habilitado;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (!habilitado) {
            log.info("Conexión MCP con xiaozhi.me desactivada (pon bicho.mcp.enabled=true en application.properties para activarla)");
            return;
        }
        if (endpointUrl == null || endpointUrl.isBlank()) {
            log.warn("bicho.mcp.endpoint-url está vacío — copia la Endpoint URL de xiaozhi.me (Extensions → MCP Endpoint) a application.properties");
            return;
        }
        httpClient = HttpClient.newHttpClient();
        conectar();
    }

    private void conectar() {
        log.info("Conectando al endpoint MCP de xiaozhi.me...");
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(endpointUrl), new EscuchadorMcp())
                .exceptionally(ex -> {
                    log.warn("No se pudo conectar al endpoint MCP, reintento en 5s: {}", ex.getMessage());
                    reintentarEn5s();
                    return null;
                });
    }

    private void reintentarEn5s() {
        CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(this::conectar);
    }

    private class EscuchadorMcp implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("Conectado al endpoint MCP de xiaozhi.me — a partir de ahora debería verse como 'Connected' en su consola");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            bufferEntrante.append(data);
            webSocket.request(1);
            if (last) {
                String mensaje = bufferEntrante.toString();
                bufferEntrante.setLength(0);
                manejarMensaje(webSocket, mensaje);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("Conexión MCP cerrada (código {}, motivo '{}') — reconectando en 5s", statusCode, reason);
            reintentarEn5s();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("Error en la conexión MCP con xiaozhi.me", error);
        }
    }

    // ---- Protocolo MCP (JSON-RPC 2.0 sobre WebSocket) ----

    private void manejarMensaje(WebSocket ws, String json) {
        try {
            JsonNode nodo = mapper.readTree(json);
            String metodo = nodo.path("method").asText(null);
            if (metodo == null) {
                return; // no es una petición/notificación que sepamos manejar
            }
            JsonNode id = nodo.get("id"); // ausente => es una notificación, no se responde

            switch (metodo) {
                case "initialize" -> responderInitialize(ws, id);
                case "notifications/initialized" -> log.info("xiaozhi.me ha confirmado la sesión MCP");
                case "tools/list" -> responderToolsList(ws, id);
                case "tools/call" -> responderToolsCall(ws, id, nodo.path("params"));
                case "ping" -> enviarRespuesta(ws, id, mapper.createObjectNode());
                default -> log.debug("Método MCP no manejado: {}", metodo);
            }
        } catch (Exception ex) {
            log.warn("No se pudo interpretar un mensaje MCP entrante: {}", json, ex);
        }
    }

    private void responderInitialize(WebSocket ws, JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.set("capabilities", mapper.createObjectNode().set("tools", mapper.createObjectNode()));

        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", "bicho-cara-mcp");
        serverInfo.put("version", "0.1.0");
        result.set("serverInfo", serverInfo);

        enviarRespuesta(ws, id, result);
    }

    private void responderToolsList(WebSocket ws, JsonNode id) {
        ObjectNode propiedadEstado = mapper.createObjectNode();
        propiedadEstado.put("type", "string");
        ArrayNode valoresPosibles = mapper.createArrayNode();
        valoresPosibles.add("reposo").add("escuchando").add("hablando");
        propiedadEstado.set("enum", valoresPosibles);

        ObjectNode propiedadTexto = mapper.createObjectNode();
        propiedadTexto.put("type", "string");
        propiedadTexto.put("description", "Subtítulo a mostrar cuando estado es 'hablando' (opcional)");

        ObjectNode propiedades = mapper.createObjectNode();
        propiedades.set("estado", propiedadEstado);
        propiedades.set("texto", propiedadTexto);

        ArrayNode requeridos = mapper.createArrayNode();
        requeridos.add("estado");

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", propiedades);
        schema.set("required", requeridos);

        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", "mostrar_cara");
        tool.put("description",
                "Actualiza la cara animada del bicho (ojos y boca) en la pantalla web. " +
                "Llámala con estado='escuchando' en cuanto el usuario empiece a hablar, " +
                "con estado='hablando' y el texto de tu respuesta justo antes de decirla, " +
                "y con estado='reposo' cuando termines de responder.");
        tool.set("inputSchema", schema);

        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool);

        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);

        enviarRespuesta(ws, id, result);
    }

    private void responderToolsCall(WebSocket ws, JsonNode id, JsonNode params) {
        String nombreHerramienta = params.path("name").asText("");
        JsonNode argumentos = params.path("arguments");

        String textoResultado;
        if ("mostrar_cara".equals(nombreHerramienta)) {
            Estado estado = Estado.desdeTexto(argumentos.path("estado").asText("reposo"));
            String texto = argumentos.path("texto").asText("");
            estadoWebSocketHandler.cambiarEstado(estado, texto);
            textoResultado = "Cara actualizada a '" + estado.comoTexto() + "'";
            log.info("xiaozhi.me llamó a mostrar_cara(estado={}, texto='{}')", estado.comoTexto(), texto);
        } else {
            textoResultado = "Herramienta desconocida: " + nombreHerramienta;
            log.warn("xiaozhi.me llamó a una herramienta MCP que no existe: {}", nombreHerramienta);
        }

        ObjectNode contenido = mapper.createObjectNode();
        contenido.put("type", "text");
        contenido.put("text", textoResultado);

        ArrayNode contenidos = mapper.createArrayNode();
        contenidos.add(contenido);

        ObjectNode result = mapper.createObjectNode();
        result.set("content", contenidos);

        enviarRespuesta(ws, id, result);
    }

    private void enviarRespuesta(WebSocket ws, JsonNode id, ObjectNode result) {
        if (id == null) {
            return; // era una notificación, no se responde
        }
        ObjectNode respuesta = mapper.createObjectNode();
        respuesta.put("jsonrpc", "2.0");
        respuesta.set("id", id);
        respuesta.set("result", result);
        try {
            ws.sendText(mapper.writeValueAsString(respuesta), true);
        } catch (Exception ex) {
            log.warn("No se pudo enviar la respuesta MCP", ex);
        }
    }
}
