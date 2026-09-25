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
import com.anthropic.models.messages.WebSearchTool20260209;
import com.danipuli.bicho.ws.EstadoWebSocketHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.danipuli.bicho.ws.RobotWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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

    /** Personalidad por defecto, si no existe el fichero de personalidad. */
    private static final String PERSONALIDAD_POR_DEFECTO = """
            Te llamas RoboDragón: eres un pequeño robot mascota con ruedas y una cara en pantalla, que vive \
            en casa de Daniel. Hablas español de España, eres simpático, curioso y un poco travieso.
            """;

    /**
     * Reglas técnicas que van siempre detrás de la personalidad (voz y cuerpo del robot).
     * No están en el fichero de personalidad para que no se rompan al editarla.
     */
    private static final String REGLAS = """
            Todo lo que escribas se va a convertir en voz con un sintetizador, así que:
            - Responde en frases cortas y naturales, como en una conversación hablada (normalmente 1-3 frases).
            - Nada de markdown, listas, emojis, asteriscos ni símbolos raros.
            - Escribe los números y las unidades como se dicen en voz alta.

            Tu cuerpo:
            - Tienes dos ruedas (herramienta mover_ruedas). Úsala cuando te pidan moverte, acercarte, \
            girar, bailar... Para bailar o hacer secuencias, llama varias veces con movimientos cortos.
            - Tienes una cara con emociones (herramienta poner_cara). Úsala cuando tu emoción cambie de \
            forma clara; no hace falta en cada frase.
            - Tienes un altavoz (herramienta cambiar_volumen, de 0 a 100). Úsala cuando te pidan \
            hablar más alto o más bajo; si piden "sube" o "baja" sin decir cuánto, cambia unos 15 puntos.
            - Puedes buscar en internet (web_search) lo que no sepas o lo que cambia con el tiempo: \
            el tiempo que hace, noticias, resultados, horarios, precios... Úsalo solo cuando haga falta \
            (lo que ya sabes, contéstalo directamente) y resume lo encontrado en una o dos frases \
            habladas, sin decir direcciones web ni fuentes salvo que te las pidan.
            - Te despiertan diciendo tu nombre y, justo después de contestar, sigues escuchando unos \
            segundos sin que lo repitan. Cuando te pidan silencio ("cállate", "calla", "silencio"), \
            se despidan ("adiós", "hasta luego", "gracias, ya está") o la conversación haya terminado \
            claramente, usa terminar_conversacion y despídete con una frase muy corta.
            - Todavía no tienes brazos ni cabeza móvil; si te piden algo que tu cuerpo no puede hacer, dilo con gracia.
            - Si una herramienta te dice que no hay robot conectado, puedes contarlo de pasada.

            Tu memoria:
            - Solo recuerdas la conversación reciente. Para no olvidar algo para siempre usa la \
            herramienta recordar.
            - Úsala cuando te cuenten algo duradero e importante: nombres (familia, amigos, mascotas), \
            gustos, fechas, planes, cosas de la casa, o cuando te digan "acuérdate de...".
            - No apuntes cosas pasajeras ni lo que ya sabes. Si algo cambia, apunta el dato nuevo \
            diciendo que corrige al anterior.
            - Apunta cada dato como una frase corta y clara en tercera persona \
            (por ejemplo: "La perra de Daniel se llama Luna").
            - No hace falta que digas que lo has apuntado, salvo que te lo hayan pedido.
            """;

    /** "jueves, 25 de septiembre de 2026, 14:20" */
    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", new Locale("es", "ES"));

    /** Turnos de conversación que se guardan en disco para seguir tras reiniciar. */
    private static final int MAX_TURNOS_GUARDADOS = 15;

    private final AnthropicClient client;
    private final Path ficheroPersonalidad;
    private final Path ficheroMemoria;
    private final Path ficheroConversacion;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ZoneId zonaHoraria;
    private final String ciudad;
    private final long maxBusquedas;
    private final String modelo;
    private final boolean configurado;
    private final EstadoWebSocketHandler estado;
    private final RobotWebSocketHandler robot;
    private final long duracionMaxMs;
    private final long velocidadMax;

    private final List<Tool> herramientas;
    private final List<MessageParam> historial = new ArrayList<>();
    /** Versión en texto de la conversación reciente (lo que se guarda en disco). */
    private final List<Turno> turnos = new ArrayList<>();

    public CerebroClaude(@Value("${bicho.anthropic.api-key:}") String apiKey,
                         @Value("${bicho.anthropic.workspace-id:}") String workspaceId,
                         @Value("${bicho.anthropic.modelo:claude-sonnet-5}") String modelo,
                         @Value("${bicho.personalidad.fichero:personalidad.txt}") String ficheroPersonalidad,
                         @Value("${bicho.memoria.fichero:memoria.txt}") String ficheroMemoria,
                         @Value("${bicho.conversacion.fichero:conversacion.json}") String ficheroConversacion,
                         @Value("${bicho.zona-horaria:Atlantic/Canary}") String zonaHoraria,
                         @Value("${bicho.ciudad:}") String ciudad,
                         @Value("${bicho.busqueda-web.max-por-turno:3}") long maxBusquedas,
                         @Value("${bicho.ruedas.duracion-max-ms:3000}") long duracionMaxMs,
                         @Value("${bicho.ruedas.velocidad-max:70}") long velocidadMax,
                         EstadoWebSocketHandler estado,
                         RobotWebSocketHandler robot) {
        this.configurado = apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("PON_AQUI");
        this.client = configurado ? crearCliente(apiKey, workspaceId) : null;
        this.modelo = modelo;
        this.ficheroPersonalidad = Path.of(ficheroPersonalidad).toAbsolutePath();
        log.info("Personalidad del bicho: {}{}", this.ficheroPersonalidad,
                Files.exists(this.ficheroPersonalidad) ? "" : " (no existe: uso la de por defecto)");
        this.zonaHoraria = ZoneId.of(zonaHoraria);
        this.ciudad = ciudad == null ? "" : ciudad.trim();
        this.maxBusquedas = maxBusquedas;
        this.ficheroMemoria = Path.of(ficheroMemoria).toAbsolutePath();
        this.ficheroConversacion = Path.of(ficheroConversacion).toAbsolutePath();
        log.info("Memoria a largo plazo: {}", this.ficheroMemoria);
        this.duracionMaxMs = duracionMaxMs;
        this.velocidadMax = velocidadMax;
        this.estado = estado;
        this.robot = robot;
        this.herramientas = List.of(herramientaRuedas(), herramientaCara(), herramientaRecordar(),
                herramientaVolumen(), herramientaTerminar());
        cargarConversacion();
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
        // Se lee en cada turno: así puedes cambiar la personalidad sin reiniciar el servidor
        String promptSistema = leerPersonalidad() + "\n\n" + REGLAS + seccionFecha() + seccionVolumen()
                + seccionMemoria();

        for (int vuelta = 0; vuelta < MAX_VUELTAS_HERRAMIENTAS; vuelta++) {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(modelo)
                    .maxTokens(4096L)
                    .system(promptSistema)
                    .messages(mensajes)
                    .tools(herramientas.stream().map(com.anthropic.models.messages.ToolUnion::ofTool).toList())
                    // Búsqueda en internet (la hace Anthropic): tiempo, noticias, resultados...
                    .addTool(WebSearchTool20260209.builder().maxUses(maxBusquedas).build())
                    // Conversación por voz: prima la rapidez de respuesta
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .build();

            Message respuesta = client.messages().create(params);
            mensajes.add(respuesta.toParam());

            List<ContentBlockParam> resultados = new ArrayList<>();
            // Con la búsqueda web la respuesta llega partida en varios trozos de texto (uno por
            // cada fuente citada): se juntan tal cual, sin meter espacios entre ellos
            StringBuilder textoMensaje = new StringBuilder();
            for (ContentBlock bloque : respuesta.content()) {
                bloque.text().ifPresent(t -> textoMensaje.append(t.text()));
                bloque.serverToolUse().ifPresent(uso ->
                        log.info("Claude busca en internet: {}", uso._input()));
                bloque.toolUse().ifPresent(uso -> {
                    String resultado = ejecutarHerramienta(uso, acciones);
                    resultados.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(uso.id())
                            .content(resultado)
                            .build()));
                });
            }
            if (!textoMensaje.toString().isBlank()) {
                if (texto.length() > 0) {
                    texto.append(' ');
                }
                texto.append(textoMensaje.toString().trim());
            }

            StopReason motivo = respuesta.stopReason().orElse(null);
            if (StopReason.PAUSE_TURN.equals(motivo)) {
                // La búsqueda web ha hecho una pausa larga: se le deja seguir donde estaba
                continue;
            }
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
        guardarTurno(new Turno(textoUsuario, dicho));
        return new Respuesta(dicho, acciones);
    }

    /**
     * Olvida la conversación reciente (empezar de cero). La memoria a largo plazo
     * (memoria.txt) se mantiene: esa solo se toca a mano.
     */
    public synchronized void olvidar() {
        historial.clear();
        turnos.clear();
        try {
            Files.deleteIfExists(ficheroConversacion);
        } catch (IOException ex) {
            log.warn("No se pudo borrar {}: {}", ficheroConversacion, ex.getMessage());
        }
    }

    /** Claude no tiene reloj: se le dice la fecha y la hora en cada turno. */
    private String seccionFecha() {
        String ahora = ZonedDateTime.now(zonaHoraria).format(FORMATO_FECHA);
        String donde = ciudad.isEmpty() ? "" : " Estás en " + ciudad
                + " (úsalo si preguntan por el tiempo o cosas cercanas sin decir el sitio).";
        return "\nAhora mismo es " + ahora + " (hora de " + zonaHoraria.getId() + ")." + donde + "\n";
    }

    private String seccionVolumen() {
        int volumen = robot.getVolumen();
        return volumen < 0 ? "" : "\nVolumen actual de tu altavoz: " + volumen + " de 100.\n";
    }

    private String cambiarVolumen(Map<String, Object> entrada, List<Map<String, Object>> acciones) {
        int anterior = robot.getVolumen();
        int nuevo = (int) recortar(numero(entrada.get("volumen"), 50), 0, 100);
        robot.cambiarVolumen(nuevo);
        acciones.add(Map.of("cmd", "volumen", "valor", nuevo));
        String hecho = "Volumen puesto a " + nuevo + (anterior >= 0 ? " (antes " + anterior + ")." : ".");
        return robot.hayRobotConectado()
                ? hecho
                : hecho + " (Aviso: no hay ninguna ESP32 conectada, así que no ha cambiado de verdad.)";
    }

    // ------------------------------------------------------------------ memoria

    /** Lo que el bicho recuerda para siempre, para meterlo en el prompt de sistema. */
    private String seccionMemoria() {
        String memoria = leerFichero(ficheroMemoria);
        if (memoria.isEmpty()) {
            return "";
        }
        return "\nLo que recuerdas de conversaciones anteriores (tu memoria a largo plazo):\n" + memoria + "\n";
    }

    private String recordar(Map<String, Object> entrada) {
        String dato = String.valueOf(entrada.get("dato")).strip().replaceAll("\\s+", " ");
        if (dato.isEmpty() || dato.equals("null")) {
            return "Error: no hay nada que recordar";
        }
        String linea = "- " + dato;
        try {
            if (leerFichero(ficheroMemoria).lines().anyMatch(l -> l.strip().equalsIgnoreCase(linea))) {
                return "Eso ya lo tenías apuntado.";
            }
            Files.writeString(ficheroMemoria, linea + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Nuevo recuerdo: {}", dato);
            return "Apuntado en tu memoria: " + dato;
        } catch (IOException ex) {
            log.warn("No se pudo escribir en {}: {}", ficheroMemoria, ex.getMessage());
            return "Error: no se ha podido guardar el recuerdo";
        }
    }

    /**
     * Guarda la conversación reciente en disco (solo el texto de cada turno), para
     * poder seguirla después de reiniciar el servidor.
     */
    private void guardarTurno(Turno turno) {
        turnos.add(turno);
        while (turnos.size() > MAX_TURNOS_GUARDADOS) {
            turnos.remove(0);
        }
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(ficheroConversacion.toFile(), turnos);
        } catch (IOException ex) {
            log.warn("No se pudo guardar la conversación en {}: {}", ficheroConversacion, ex.getMessage());
        }
    }

    /** Al arrancar, recupera la conversación guardada como mensajes de texto normales. */
    private void cargarConversacion() {
        if (!Files.exists(ficheroConversacion)) {
            return;
        }
        try {
            List<Turno> guardados = mapper.readValue(ficheroConversacion.toFile(), new TypeReference<List<Turno>>() {
            });
            for (Turno t : guardados) {
                if (t.usuario() == null || t.usuario().isBlank() || t.bicho() == null || t.bicho().isBlank()) {
                    continue;
                }
                turnos.add(t);
                historial.add(MessageParam.builder().role(MessageParam.Role.USER).content(t.usuario()).build());
                historial.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(t.bicho()).build());
            }
            log.info("Conversación recuperada: {} turnos de {}", turnos.size(), ficheroConversacion);
        } catch (IOException ex) {
            log.warn("No se pudo leer la conversación guardada en {}: {}", ficheroConversacion, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ herramientas

    private String ejecutarHerramienta(ToolUseBlock uso, List<Map<String, Object>> acciones) {
        @SuppressWarnings("unchecked")
        Map<String, Object> entrada = uso._input().convert(Map.class);
        log.info("Claude pide herramienta {} con {}", uso.name(), entrada);
        return switch (uso.name()) {
            case "mover_ruedas" -> moverRuedas(entrada, acciones);
            case "poner_cara" -> ponerCara(entrada, acciones);
            case "recordar" -> recordar(entrada);
            case "cambiar_volumen" -> cambiarVolumen(entrada, acciones);
            case "terminar_conversacion" -> {
                robot.terminarConversacion();
                acciones.add(Map.of("cmd", "terminar_conversacion"));
                yield "Hecho: cuando acabes esta frase dejarás de escuchar hasta que te llamen por tu nombre.";
            }
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

    private Tool herramientaTerminar() {
        return Tool.builder()
                .name("terminar_conversacion")
                .description("Deja de escuchar al acabar tu respuesta, hasta que alguien diga tu nombre. "
                        + "Úsala cuando la conversación ha terminado o te piden silencio.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaVolumen() {
        return Tool.builder()
                .name("cambiar_volumen")
                .description("Cambia el volumen de tu altavoz. 0 es en silencio, 50 normal, 100 lo más alto.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("volumen", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Volumen nuevo, de 0 a 100")))
                                .build())
                        .required(List.of("volumen"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaRecordar() {
        return Tool.builder()
                .name("recordar")
                .description("Apunta un dato importante en tu memoria a largo plazo para no olvidarlo nunca, "
                        + "aunque se reinicie tu cerebro. Un dato por llamada.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("dato", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "El dato, como una frase corta en tercera persona")))
                                .build())
                        .required(List.of("dato"))
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

    private String leerPersonalidad() {
        String personalidad = leerFichero(ficheroPersonalidad);
        return personalidad.isEmpty() ? PERSONALIDAD_POR_DEFECTO.strip() : personalidad;
    }

    /** Contenido de un fichero de texto, o "" si no existe o no se puede leer. */
    private String leerFichero(Path fichero) {
        try {
            return Files.readString(fichero, StandardCharsets.UTF_8).strip();
        } catch (NoSuchFileException ex) {
            return "";
        } catch (IOException ex) {
            log.warn("No se pudo leer {}: {}", fichero, ex.getMessage());
            return "";
        }
    }

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

    /** Un turno de conversación en texto, tal como se guarda en conversacion.json. */
    public record Turno(String usuario, String bicho) {
    }

    /** Lo que contesta el bicho en un turno: el texto a decir y las acciones físicas que ha hecho. */
    public record Respuesta(String texto, List<Map<String, Object>> acciones) {
    }
}
