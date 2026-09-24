package com.danipuli.bicho.cerebro;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.danipuli.bicho.ws.EstadoWebSocketHandler;
import com.danipuli.bicho.ws.RobotWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * El "cerebro" del bicho: recibe lo que le has dicho (texto), se lo pasa a Claude junto con
 * la conversación anterior y las herramientas que puede usar (mover las ruedas, poner cara),
 * ejecuta las herramientas que Claude pida y devuelve lo que el bicho tiene que decir.
 *
 * Claude nunca mueve los motores directamente: pide una herramienta, y aquí se validan y
 * recortan los valores (velocidad y duración máximas) antes de mandar nada a la ESP32.
 */
@Component
public class CerebroClaude {

    private static final Logger log = LoggerFactory.getLogger(CerebroClaude.class);

    /** Mensajes de conversación que se recuerdan (se descartan los más antiguos). */
    private static final int MAX_MENSAJES_HISTORIAL = 30;

    /** Vueltas máximas de herramientas por turno, por si acaso. */
    private static final int MAX_VUELTAS_HERRAMIENTAS = 5;

    private static final Set<String> ACCIONES_RUEDAS =
            Set.of("adelante", "atras", "girar_izquierda", "girar_derecha", "parar");
    private static final Set<String> EMOCIONES =
            Set.of("neutral", "contento", "triste", "sorprendido", "enfadado", "pensativo");

    private static final String PROMPT_SISTEMA = """
            Eres "el bicho", un pequeño robot mascota con ruedas y una cara en pantalla, que vive en \
            casa de Daniel. Hablas español de España, eres simpático, curioso y un poco travieso.

            Todo lo que escribas se va a convertir en voz con un sintetizador, así que:
            - Responde en frases cortas y naturales, como en una conversación hablada (normalmente 1-3 frases).
            - Nada de markdown, listas, emojis, asteriscos ni símbolos raros.
            - Escribe los números y las unidades como se dicen en voz alta.

            Tu cuerpo:
            - Tienes dos ruedas (herramienta mover_ruedas). Úsala cuando te pidan moverte, acercarte, \
            girar, bailar... Para bailar o hacer secuencias, llama varias veces con movimientos cortos.
            - Tienes una cara con emociones (herramienta poner_cara). Úsala cuando tu emoción cambie de \
            forma clara; no hace falta en cada frase.
            - Todavía no tienes brazos ni cabeza móvil; si te piden algo que tu cuerpo no puede hacer, dilo con gracia.
            - Si una herramienta te dice que no hay robot conectado, puedes contarlo de pasada.
            """;

    private final AnthropicClient client;
    private final String modelo;
    private final boolean configurado;
    private final EstadoWebSocketHandler estado;
    private final RobotWebSocketHandler robot;
    private final long duracionMaxMs;
    private final long velocidadMax;

    private final List<Tool> herramientas;
    private final List<MessageParam> historial = new ArrayList<>();

    public CerebroClaude(@Value("${bicho.anthropic.api-key:}") String apiKey,
                         @Value("${bicho.anthropic.workspace-id:}") String workspaceId,
                         @Value("${bicho.anthropic.modelo:claude-sonnet-5}") String modelo,
                         @Value("${bicho.ruedas.duracion-max-ms:3000}") long duracionMaxMs,
                         @Value("${bicho.ruedas.velocidad-max:70}") long velocidadMax,
                         EstadoWebSocketHandler estado,
                         RobotWebSocketHandler robot) {
        this.configurado = apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("PON_AQUI");
        this.client = configurado ? crearCliente(apiKey, workspaceId) : null;
        this.modelo = modelo;
        this.duracionMaxMs = duracionMaxMs;
        this.velocidadMax = velocidadMax;
        this.estado = estado;
        this.robot = robot;
        this.herramientas = List.of(herramientaRuedas(), herramientaCara());
        if (!configurado) {
            log.warn("Falta la API key de Anthropic: ponla en src/main/resources/secrets.properties (bicho.anthropic.api-key)");
        }
    }

    /**
     * Si la API key no pertenece a un workspace, Anthropic exige decir cuál usar con la
     * cabecera anthropic-workspace-id (bicho.anthropic.workspace-id en secrets.properties).
     */
    private static AnthropicClient crearCliente(String apiKey, String workspaceId) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
        if (workspaceId != null && !workspaceId.isBlank() && !workspaceId.startsWith("PON_AQUI")) {
            builder.putHeader("anthropic-workspace-id", workspaceId.trim());
        }
        return builder.build();
    }

    public boolean configurado() {
        return configurado;
    }

    /**
     * Un turno de conversación: le dices algo al bicho y devuelve lo que contesta
     * (las herramientas que pida Claude ya se han ejecutado al volver).
     */
    public synchronized Respuesta conversar(String textoUsuario) {
        if (!configurado) {
            throw new IllegalStateException(
                    "Falta la API key de Anthropic: ponla en src/main/resources/secrets.properties (bicho.anthropic.api-key)");
        }

        List<MessageParam> mensajes = new ArrayList<>(historial);
        mensajes.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(textoUsuario)
                .build());

        StringBuilder texto = new StringBuilder();
        List<Map<String, Object>> acciones = new ArrayList<>();

        for (int vuelta = 0; vuelta < MAX_VUELTAS_HERRAMIENTAS; vuelta++) {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(modelo)
                    .maxTokens(4096L)
                    .system(PROMPT_SISTEMA)
                    .messages(mensajes)
                    .tools(herramientas.stream().map(com.anthropic.models.messages.ToolUnion::ofTool).toList())
                    // Conversación por voz: prima la rapidez de respuesta
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .build();

            Message respuesta = client.messages().create(params);
            mensajes.add(respuesta.toParam());

            List<ContentBlockParam> resultados = new ArrayList<>();
            for (ContentBlock bloque : respuesta.content()) {
                bloque.text().ifPresent(t -> {
                    if (texto.length() > 0) {
                        texto.append(' ');
                    }
                    texto.append(t.text().trim());
                });
                bloque.toolUse().ifPresent(uso -> {
                    String resultado = ejecutarHerramienta(uso, acciones);
                    resultados.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(uso.id())
                            .content(resultado)
                            .build()));
                });
            }

            StopReason motivo = respuesta.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(motivo)) {
                log.warn("Claude ha rechazado responder a: {}", textoUsuario);
                // No guardamos este turno en el historial
                return new Respuesta("Uy, de eso prefiero no hablar.", acciones);
            }
            if (StopReason.TOOL_USE.equals(motivo) && !resultados.isEmpty()) {
                mensajes.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(resultados)
                        .build());
                continue;
            }
            break;
        }

        guardarHistorial(mensajes);
        String dicho = texto.toString().trim();
        log.info("Tú: '{}' → bicho: '{}' (acciones: {})", textoUsuario, dicho, acciones.size());
        return new Respuesta(dicho, acciones);
    }

    /** Olvida la conversación (empezar de cero). */
    public synchronized void olvidar() {
        historial.clear();
    }

    // ------------------------------------------------------------------ herramientas

    private String ejecutarHerramienta(ToolUseBlock uso, List<Map<String, Object>> acciones) {
        @SuppressWarnings("unchecked")
        Map<String, Object> entrada = uso._input().convert(Map.class);
        log.info("Claude pide herramienta {} con {}", uso.name(), entrada);
        return switch (uso.name()) {
            case "mover_ruedas" -> moverRuedas(entrada, acciones);
            case "poner_cara" -> ponerCara(entrada, acciones);
            default -> "Error: herramienta desconocida " + uso.name();
        };
    }

    private String moverRuedas(Map<String, Object> entrada, List<Map<String, Object>> acciones) {
        String accion = String.valueOf(entrada.get("accion"));
        if (!ACCIONES_RUEDAS.contains(accion)) {
            return "Error: acción no válida. Usa una de " + ACCIONES_RUEDAS;
        }
        long velocidad = recortar(numero(entrada.get("velocidad"), 50), 0, velocidadMax);
        long duracionMs = recortar(numero(entrada.get("duracion_ms"), 1000), 0, duracionMaxMs);
        if (accion.equals("parar")) {
            velocidad = 0;
            duracionMs = 0;
        }

        Map<String, Object> comando = new LinkedHashMap<>();
        comando.put("cmd", "ruedas");
        comando.put("accion", accion);
        comando.put("velocidad", velocidad);
        comando.put("duracion_ms", duracionMs);
        robot.enviarComando(comando);
        acciones.add(comando);

        String hecho = "Hecho: " + accion + " a velocidad " + velocidad + " durante " + duracionMs + " ms.";
        return robot.hayRobotConectado()
                ? hecho
                : hecho + " (Aviso: ahora mismo no hay ninguna ESP32 conectada, así que no te has movido de verdad.)";
    }

    private String ponerCara(Map<String, Object> entrada, List<Map<String, Object>> acciones) {
        String emocion = String.valueOf(entrada.get("emocion"));
        if (!EMOCIONES.contains(emocion)) {
            return "Error: emoción no válida. Usa una de " + EMOCIONES;
        }
        estado.ponerEmocion(emocion);
        acciones.add(Map.of("cmd", "cara", "emocion", emocion));
        return "Cara puesta: " + emocion;
    }

    private Tool herramientaRuedas() {
        return Tool.builder()
                .name("mover_ruedas")
                .description("Mueve las dos ruedas del robot. Cada llamada es un movimiento corto que se para solo "
                        + "al acabar la duración. Para secuencias (bailar, dar una vuelta...) haz varias llamadas.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("accion", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.copyOf(ACCIONES_RUEDAS),
                                        "description", "Qué movimiento hacer")))
                                .putAdditionalProperty("velocidad", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Velocidad de 0 a 100 (50 es tranquilo; más de "
                                                + velocidadMax + " se recorta)")))
                                .putAdditionalProperty("duracion_ms", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Cuánto dura el movimiento en milisegundos (máximo "
                                                + duracionMaxMs + ")")))
                                .build())
                        .required(List.of("accion", "velocidad", "duracion_ms"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaCara() {
        return Tool.builder()
                .name("poner_cara")
                .description("Cambia la expresión de la cara del robot en su pantalla.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("emocion", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.copyOf(EMOCIONES))))
                                .build())
                        .required(List.of("emocion"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    // ------------------------------------------------------------------ utilidades

    /**
     * Guarda la conversación recortando lo más antiguo. Solo se corta justo antes de un
     * mensaje del usuario con texto (no un resultado de herramienta), para no dejar
     * herramientas "huérfanas" al principio del historial.
     */
    private void guardarHistorial(List<MessageParam> mensajes) {
        int inicio = 0;
        while (mensajes.size() - inicio > MAX_MENSAJES_HISTORIAL) {
            inicio++;
            while (inicio < mensajes.size() && !esTextoDeUsuario(mensajes.get(inicio))) {
                inicio++;
            }
        }
        historial.clear();
        historial.addAll(mensajes.subList(inicio, mensajes.size()));
    }

    private static boolean esTextoDeUsuario(MessageParam mensaje) {
        return mensaje.role().equals(MessageParam.Role.USER) && mensaje.content().isString();
    }

    private static long numero(Object valor, long porDefecto) {
        return valor instanceof Number n ? n.longValue() : porDefecto;
    }

    private static long recortar(long valor, long min, long max) {
        return Math.max(min, Math.min(max, valor));
    }

    /** Lo que contesta el bicho en un turno: el texto a decir y las acciones físicas que ha hecho. */
    public record Respuesta(String texto, List<Map<String, Object>> acciones) {
    }
}
