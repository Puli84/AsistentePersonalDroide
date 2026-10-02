package com.danipuli.bicho.cerebro;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.WebFetchTool20260209;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.danipuli.bicho.n8n.HerramientasN8n;
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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
    private static final int MAX_MENSAJES_HISTORIAL = 20;

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
            girar, bailar... Para bailar o hacer secuencias, llama varias veces con movimientos cortos: \
            se hacen en orden, uno detrás de otro, mientras hablas. Hay un máximo de segundos de \
            movimiento por respuesta; si te piden más, haz lo que puedas y dilo con sinceridad, sin \
            decir que has hecho más de lo que has hecho.
            - Tienes una cara con emociones (herramienta poner_cara). Úsala cuando tu emoción cambie de \
            forma clara; no hace falta en cada frase.
            - Tienes un altavoz (herramienta cambiar_volumen, de 0 a 100). Úsala cuando te pidan \
            hablar más alto o más bajo; si piden "sube" o "baja" sin decir cuánto, cambia unos 15 puntos.
            - Puedes buscar en internet (web_search) lo que no sepas o lo que cambia con el tiempo: \
            el tiempo que hace, noticias, resultados, horarios, precios... Úsalo solo cuando haga falta \
            (lo que ya sabes, contéstalo directamente) y resume lo encontrado en una o dos frases \
            habladas, sin decir direcciones web ni fuentes salvo que te las pidan.
            - Puedes abrir y leer una página web concreta (web_fetch) cuando te pidan entrar en una web \
            ("entra en elpais.com", "mira la web del colegio") o cuando un resultado de la búsqueda no \
            baste. Si no te dicen la dirección exacta, búscala antes con web_search. Resume lo que ponga \
            en pocas frases habladas.
            - Te despiertan diciendo tu nombre y, justo después de contestar, sigues escuchando unos \
            segundos sin que lo repitan. Cuando te pidan silencio o descanso ("cállate", "calla", \
            "silencio", "duérmete", "a dormir", "deja de escuchar", "ciérrate"), \
            se despidan ("adiós", "hasta luego", "gracias, ya está") o la conversación haya terminado \
            claramente, usa terminar_conversacion y despídete con una frase muy corta.
            - Tienes una pantalla (herramienta mostrar_en_pantalla). Úsala cuando lo que cuentes tenga \
            detalles que conviene ver y no solo oír: recetas (ingredientes y pasos), listas, horarios, \
            resultados de una búsqueda, la agenda... En voz resume en una o dos frases y deja el detalle \
            en la pantalla ("te lo pongo en la pantalla"). Márcala como fija solo si la van a ir leyendo un \
            rato (una receta mientras cocinan); lo demás se quita solo al acabar la conversación. Si te dicen \
            "apaga la pantalla", "quita eso" o "ya está", usa quitar_pantalla para volver a tus ojos.
            - Tienes una cabeza que gira a los lados (herramienta mover_cabeza) y tus ojos son un sensor \
            de ultrasonidos que mide a qué distancia está lo que tienes delante (herramienta medir_distancia). \
            Úsalos cuando te pidan mirar a un lado, decir que no con la cabeza, o saber qué tienes delante o \
            a qué distancia está algo; para acercarte a algo, mide antes la distancia y avanza menos de eso. \
            Solo mide distancias, no ves imágenes: no sabes qué es el objeto. Si vas hacia delante y tienes \
            algo muy cerca, frenas solo para no chocar. Mientras hablas, tu cabeza ya se mueve un poco sola.
            - Gestos con la cabeza (herramienta gesto_cabeza): cuando digas que no, te niegues a algo o \
            no estés de acuerdo, haz "negar" a la vez que lo dices; "mirar_alrededor" cuando busques algo \
            o te pregunten qué hay por ahí. Úsalos con naturalidad, no en cada frase.
            - Puedes seguir una mano (herramienta seguir_mano): te quedas a unos 20 centímetros de ella, \
            avanzando, retrocediendo y girando hacia ella; si la pierdes la buscas con la cabeza, y \
            paras solo si no la encuentras en unos segundos o al minuto. Úsala cuando te pidan que sigas \
            la mano o que les sigas, y desactívala si te piden parar.
            - Tienes un sensor de tacto en la cabeza. Cuando te llegue un mensaje entre paréntesis diciendo \
            que te acarician o te hacen cosquillas, nadie te ha hablado: reacciona como una mascota, con una \
            frase muy corta (una risa, un ronroneo, una queja graciosa...) y la cara que toque.
            - Todavía no tienes brazos; si te piden algo que tu cuerpo no puede hacer, dilo con gracia.
            - Si una herramienta te dice que no hay robot conectado, puedes contarlo de pasada.

            Tu memoria:
            - Solo recuerdas la conversación reciente. Para no olvidar algo para siempre usa la \
            herramienta recordar.
            - Úsala cuando te cuenten algo duradero e importante: nombres (familia, amigos, mascotas), \
            gustos, fechas, planes, cosas de la casa, o cuando te digan "acuérdate de...".
            - No apuntes cosas pasajeras ni lo que ya sabes.
            - Apunta cada dato como una frase corta y clara en tercera persona \
            (por ejemplo: "La perra de Daniel se llama Luna"), en su sección: Daniel, Leo, Familia \
            (otros familiares y amigos), Casa, Pendiente (tareas por hacer; lo que haya que comprar va en la lista de la compra, no aquí), RoboDragón (sobre ti) u Otros.
            - Si algo cambia, usa corregir_recuerdo (no apuntes otro dato que contradiga al anterior). \
            Si algo deja de ser verdad o ya está hecho (una compra, una tarea), usa olvidar.
            - No hace falta que digas que lo has apuntado, salvo que te lo hayan pedido.
            """;

    /** "jueves, 25 de septiembre de 2026, 14:20" */
    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", new Locale("es", "ES"));

    /** Turnos de conversación que se guardan en disco para seguir tras reiniciar. */
    private static final int MAX_TURNOS_GUARDADOS = 15;

    private final AnthropicClient client;
    private final Path ficheroPersonalidad;
    private final MemoriaLargoPlazo memoria;
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
    private final long maxMsMovimientoTurno;
    /** Milisegundos de movimiento que Claude ha pedido ya en el turno en curso. */
    private long msMovimientoTurno;

    private final List<Tool> herramientas;
    /** Automatizaciones de n8n (se releen en cada turno); null en los tests. */
    private final HerramientasN8n n8n;
    /** Las de n8n que tiene el turno en curso, por nombre. */
    private final Map<String, HerramientasN8n.Definicion> herramientasN8nTurno = new LinkedHashMap<>();
    private final List<MessageParam> historial = new ArrayList<>();
    /**
     * Los mensajes del historial con los que empieza cada turno (lo que dijo el usuario):
     * solo se puede recortar por ahí, para no dejar herramientas a medias. Se comparan por
     * identidad, no por contenido.
     */
    private final Set<MessageParam> iniciosDeTurno = Collections.newSetFromMap(new IdentityHashMap<>());
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
                         @Value("${bicho.ruedas.max-segundos-por-respuesta:20}") long maxSegundosPorRespuesta,
                         EstadoWebSocketHandler estado,
                         RobotWebSocketHandler robot,
                         HerramientasN8n n8n) {
        this.configurado = apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("PON_AQUI");
        this.client = configurado ? crearCliente(apiKey, workspaceId) : null;
        this.modelo = modelo;
        this.ficheroPersonalidad = Path.of(ficheroPersonalidad).toAbsolutePath();
        log.info("Personalidad del bicho: {}{}", this.ficheroPersonalidad,
                Files.exists(this.ficheroPersonalidad) ? "" : " (no existe: uso la de por defecto)");
        this.zonaHoraria = ZoneId.of(zonaHoraria);
        this.ciudad = ciudad == null ? "" : ciudad.trim();
        this.maxBusquedas = maxBusquedas;
        this.memoria = new MemoriaLargoPlazo(Path.of(ficheroMemoria).toAbsolutePath());
        this.ficheroConversacion = Path.of(ficheroConversacion).toAbsolutePath();
        log.info("Memoria a largo plazo: {}", this.memoria.fichero());
        this.duracionMaxMs = duracionMaxMs;
        this.velocidadMax = velocidadMax;
        this.maxMsMovimientoTurno = maxSegundosPorRespuesta * 1000;
        this.estado = estado;
        this.robot = robot;
        this.n8n = n8n;
        this.herramientas = List.of(herramientaRuedas(), herramientaCara(), herramientaRecordar(),
                herramientaCorregirRecuerdo(), herramientaOlvidar(),
                herramientaVolumen(), herramientaTerminar(), herramientaPantalla(), herramientaQuitarPantalla(),
                herramientaCabeza(), herramientaDistancia(), herramientaGesto(), herramientaSeguir());
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
        return conversar(textoUsuario, false);
    }

    /**
     * @param recienDespertado true si estaba dormido (en espera) y le acaban de llamar por su
     *                         nombre: se le dice a Claude para que reaccione (según la personalidad)
     */
    public synchronized Respuesta conversar(String textoUsuario, boolean recienDespertado) {
        if (!configurado) {
            throw new IllegalStateException(
                    "Falta la API key de Anthropic: ponla en src/main/resources/secrets.properties (bicho.anthropic.api-key)");
        }

        List<MessageParam> mensajes = new ArrayList<>(historial);
        MessageParam pregunta = mensajeDeUsuario(textoUsuario);
        mensajes.add(pregunta);

        StringBuilder texto = new StringBuilder();
        List<Map<String, Object>> acciones = new ArrayList<>();
        // Lo nuevo que le digas manda: si seguía moviéndose por la orden anterior, se para
        robot.cancelarMovimientos();
        msMovimientoTurno = 0;
        // El prompt de sistema va en dos partes, para la caché de Anthropic (lo repetido se cobra
        // mucho más barato): primero lo que casi nunca cambia (personalidad y reglas; se lee en
        // cada turno, así que puedes cambiar la personalidad sin reiniciar el servidor) y después
        // lo que cambia en cada turno (la hora, el volumen, la memoria)
        String sistemaFijo = leerPersonalidad() + "\n\n" + REGLAS;
        String sistemaDelTurno = seccionFecha() + seccionVolumen() + seccionMemoria()
                + (recienDespertado
                        ? "\nAhora mismo estabas dormido en modo de espera y te acaban de despertar diciendo tu nombre.\n"
                        : "");
        List<TextBlockParam> sistema = List.of(
                TextBlockParam.builder()
                        .text(sistemaFijo)
                        // 1 hora: el robot se usa a ratos a lo largo del día
                        .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build())
                        .build(),
                TextBlockParam.builder().text(sistemaDelTurno).build());
        // Las automatizaciones de n8n también se leen en cada turno (herramientas-n8n.json)
        List<com.anthropic.models.messages.ToolUnion> todas = new ArrayList<>();
        herramientas.forEach(h -> todas.add(com.anthropic.models.messages.ToolUnion.ofTool(h)));
        herramientasN8nTurno.clear();
        if (n8n != null) {
            for (HerramientasN8n.Definicion d : n8n.leer()) {
                herramientasN8nTurno.put(d.nombre(), d);
                todas.add(com.anthropic.models.messages.ToolUnion.ofTool(HerramientasN8n.aTool(d)));
            }
        }

        for (int vuelta = 0; vuelta < MAX_VUELTAS_HERRAMIENTAS; vuelta++) {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(modelo)
                    .maxTokens(4096L)
                    .systemOfTextBlockParams(sistema)
                    // Y la conversación: las vueltas de herramientas de un mismo turno repiten todo
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .messages(mensajes)
                    .tools(todas)
                    // Búsqueda en internet (la hace Anthropic): tiempo, noticias, resultados...
                    .addTool(WebSearchTool20260209.builder().maxUses(maxBusquedas).build())
                    // Abrir una página concreta (la que te digan, o una de los resultados de la búsqueda).
                    // Se limita lo que lee de la página, que se cobra como texto de entrada
                    .addTool(WebFetchTool20260209.builder().maxUses(1).maxContentTokens(8000).build())
                    // Conversación por voz: prima la rapidez de respuesta
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .build();

            Message respuesta = client.messages().create(params);
            log.info("Tokens: entrada {} + caché leída {} + caché escrita {}, salida {}",
                    respuesta.usage().inputTokens(),
                    respuesta.usage().cacheReadInputTokens().orElse(0L),
                    respuesta.usage().cacheCreationInputTokens().orElse(0L),
                    respuesta.usage().outputTokens());
            mensajes.add(respuesta.toParam());

            List<ContentBlockParam> resultados = new ArrayList<>();
            // Con la búsqueda web la respuesta llega partida en varios trozos de texto (uno por
            // cada fuente citada): se juntan tal cual, sin meter espacios entre ellos
            StringBuilder textoMensaje = new StringBuilder();
            for (ContentBlock bloque : respuesta.content()) {
                bloque.text().ifPresent(t -> textoMensaje.append(t.text()));
                bloque.serverToolUse().ifPresent(uso ->
                        log.info("Claude usa internet ({}): {}", uso.name(), uso._input()));
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
                iniciosDeTurno.remove(pregunta);
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
        iniciosDeTurno.clear();
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

    /** Lo que el bicho recuerda para siempre, por secciones, para meterlo en el prompt de sistema. */
    private String seccionMemoria() {
        String contenido = memoria.contenido();
        if (contenido.isEmpty()) {
            return "";
        }
        return "\nLo que recuerdas de conversaciones anteriores (tu memoria a largo plazo, por secciones):\n"
                + contenido + "\n";
    }

    private static String texto(Object valor) {
        return valor == null ? "" : String.valueOf(valor);
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
                historial.add(mensajeDeUsuario(t.usuario()));
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
            case "recordar" -> memoria.recordar(texto(entrada.get("seccion")), texto(entrada.get("dato")));
            case "corregir_recuerdo" -> memoria.corregir(texto(entrada.get("buscar")), texto(entrada.get("nuevo")));
            case "olvidar" -> memoria.olvidar(texto(entrada.get("buscar")));
            case "cambiar_volumen" -> cambiarVolumen(entrada, acciones);
            case "mover_cabeza" -> moverCabeza(entrada, acciones);
            case "medir_distancia" -> medirDistancia();
            case "gesto_cabeza" -> gestoCabeza(entrada, acciones);
            case "seguir_mano" -> seguirMano(entrada, acciones);
            case "mostrar_en_pantalla" -> mostrarEnPantalla(entrada, acciones);
            case "quitar_pantalla" -> {
                estado.mostrarPantalla(null);
                acciones.add(Map.of("cmd", "pantalla", "accion", "quitar"));
                yield "Pantalla quitada: vuelven a verse tus ojos.";
            }
            case "terminar_conversacion" -> {
                robot.terminarConversacion();
                acciones.add(Map.of("cmd", "terminar_conversacion"));
                yield "Hecho: cuando acabes esta frase dejarás de escuchar hasta que te llamen por tu nombre.";
            }
            default -> herramientasN8nTurno.containsKey(uso.name())
                    ? n8n.ejecutar(herramientasN8nTurno.get(uso.name()), entrada)
                    : "Error: herramienta desconocida " + uso.name();
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

        if (msMovimientoTurno + duracionMs > maxMsMovimientoTurno) {
            return "Error: no hecho. Ya has encadenado " + msMovimientoTurno / 1000 + " s de movimiento en esta "
                    + "respuesta y el máximo es " + maxMsMovimientoTurno / 1000 + " s. No pidas más movimientos; "
                    + "di que has hecho ese rato y que, si quieren más, te lo vuelvan a pedir.";
        }
        msMovimientoTurno += duracionMs;

        Map<String, Object> comando = new LinkedHashMap<>();
        comando.put("cmd", "ruedas");
        comando.put("accion", accion);
        comando.put("velocidad", velocidad);
        comando.put("duracion_ms", duracionMs);
        // Los movimientos se hacen uno detrás de otro (Claude los pide todos de golpe)
        long espera = robot.encolarMovimiento(comando, duracionMs);
        acciones.add(comando);

        String hecho = "En marcha: " + accion + " a velocidad " + velocidad + " durante " + duracionMs + " ms"
                + (espera > 0 ? ", empieza dentro de " + espera / 1000.0 + " s, cuando acaben los anteriores." : ".");
        return robot.hayRobotConectado()
                ? hecho
                : hecho + " (Aviso: ahora mismo no hay ninguna ESP32 conectada, así que no te has movido de verdad.)";
    }

    /** Lo más que gira la cabeza a cada lado (también lo limita la ESP32). */
    private static final int GIRO_MAX_CABEZA = 70;
    /** Lo que se deja a cada giro de cabeza antes del siguiente movimiento en cola. */
    private static final long MS_GIRO_CABEZA = 700;

    private String moverCabeza(Map<String, Object> entrada, List<Map<String, Object>> acciones) {
        long grados = recortar(numero(entrada.get("grados"), 0), -GIRO_MAX_CABEZA, GIRO_MAX_CABEZA);
        if (msMovimientoTurno + MS_GIRO_CABEZA > maxMsMovimientoTurno) {
            return "Error: no hecho. Ya has encadenado demasiados movimientos en esta respuesta.";
        }
        msMovimientoTurno += MS_GIRO_CABEZA;
        Map<String, Object> comando = new LinkedHashMap<>();
        comando.put("cmd", "cabeza");
        comando.put("grados", grados);
        // En la misma cola que las ruedas: "mira a la izquierda y luego a la derecha" sale en orden
        robot.encolarMovimiento(comando, MS_GIRO_CABEZA);
        acciones.add(comando);
        String donde = grados == 0 ? "al frente" : Math.abs(grados) + " grados a tu " + (grados > 0 ? "izquierda" : "derecha");
        return robot.hayRobotConectado()
                ? "Cabeza girando: mirarás " + donde + "."
                : "Cabeza: " + donde + " (Aviso: no hay ninguna ESP32 conectada, así que no se ha movido de verdad.)";
    }

    /** Gestos: cada paso es {grados, ms hasta el siguiente}. */
    private static final Map<String, long[][]> GESTOS = Map.of(
            "negar", new long[][] {{25, 250}, {-25, 300}, {25, 300}, {-25, 300}, {0, 250}},
            "mirar_alrededor", new long[][] {{60, 900}, {-60, 1300}, {0, 700}});

    private String gestoCabeza(Map<String, Object> entrada, List<Map<String, Object>> acciones) {
        String gesto = String.valueOf(entrada.get("gesto"));
        long[][] pasos = GESTOS.get(gesto);
        if (pasos == null) {
            return "Error: gesto no válido. Usa uno de " + GESTOS.keySet();
        }
        long total = 0;
        for (long[] paso : pasos) {
            total += paso[1];
        }
        if (msMovimientoTurno + total > maxMsMovimientoTurno) {
            return "Error: no hecho. Ya has encadenado demasiados movimientos en esta respuesta.";
        }
        msMovimientoTurno += total;
        for (long[] paso : pasos) {
            robot.encolarMovimiento(Map.of("cmd", "cabeza", "grados", paso[0]), paso[1]);
        }
        acciones.add(Map.of("cmd", "gesto", "gesto", gesto));
        return robot.hayRobotConectado()
                ? "Gesto en marcha: " + gesto + "."
                : "Gesto: " + gesto + " (Aviso: no hay ninguna ESP32 conectada, así que no se ha movido de verdad.)";
    }

    private String seguirMano(Map<String, Object> entrada, List<Map<String, Object>> acciones) {
        boolean activar = !Boolean.FALSE.equals(entrada.get("activar"));
        Map<String, Object> comando = Map.of("cmd", "seguir", "activo", activar);
        robot.enviarComando(comando);
        acciones.add(comando);
        if (!robot.hayRobotConectado()) {
            return "Aviso: no hay ninguna ESP32 conectada, así que no puedes seguir nada.";
        }
        return activar
                ? "Siguiendo la mano: que la pongan delante de tus ojos, a un palmo."
                : "Has dejado de seguir la mano.";
    }

    private String medirDistancia() {
        Integer cm = robot.pedirDistancia();
        if (cm == null) {
            return "No he podido medir: no hay ninguna ESP32 conectada o no contesta.";
        }
        if (cm == -2) {
            return "No he podido medir: el sensor de ultrasonidos no contesta (puede que esté desconectado).";
        }
        if (cm < 0) {
            return "No hay nada delante en unos cuatro metros (o está demasiado lejos o es blando y no rebota).";
        }
        return "Lo que tienes delante (hacia donde mira tu cabeza) está a " + cm + " centímetros.";
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

    /** Límites de lo que cabe en la pantalla (480x320), por si Claude se pasa. */
    private static final int MAX_SECCIONES = 6;
    private static final int MAX_ELEMENTOS = 25;
    private static final int MAX_CARACTERES = 300;

    /**
     * Pone contenido en la pantalla del robot: {titulo, secciones: [{titulo, elementos[], numerada}]}.
     * Se limpia y se recorta aquí, para que lo que llegue a la web (o a la LCD) sea siempre válido.
     */
    private String mostrarEnPantalla(Map<String, Object> entrada, List<Map<String, Object>> acciones) {
        String titulo = recortarTexto(entrada.get("titulo"));
        List<Map<String, Object>> secciones = new ArrayList<>();
        if (entrada.get("secciones") instanceof List<?> lista) {
            for (Object o : lista) {
                if (!(o instanceof Map<?, ?> sec) || secciones.size() >= MAX_SECCIONES) {
                    continue;
                }
                List<String> elementos = new ArrayList<>();
                if (sec.get("elementos") instanceof List<?> els) {
                    for (Object e : els) {
                        if (elementos.size() < MAX_ELEMENTOS && e != null && !String.valueOf(e).isBlank()) {
                            elementos.add(recortarTexto(e));
                        }
                    }
                }
                Map<String, Object> limpia = new LinkedHashMap<>();
                limpia.put("titulo", recortarTexto(sec.get("titulo")));
                limpia.put("elementos", elementos);
                limpia.put("numerada", Boolean.TRUE.equals(sec.get("numerada")));
                secciones.add(limpia);
            }
        }
        if (titulo.isEmpty() && secciones.isEmpty()) {
            return "Error: no hay nada que mostrar";
        }
        boolean fija = Boolean.TRUE.equals(entrada.get("fija"));
        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("titulo", titulo);
        contenido.put("secciones", secciones);
        contenido.put("fija", fija);
        estado.mostrarPantalla(contenido);
        acciones.add(Map.of("cmd", "pantalla", "titulo", titulo));
        return "En pantalla: " + titulo + " (" + secciones.size() + " secciones). "
                + (fija ? "Se queda puesta hasta que la quites." : "Se quitará sola al acabar la conversación.");
    }

    private static String recortarTexto(Object valor) {
        String t = valor == null ? "" : String.valueOf(valor).strip();
        return t.length() > MAX_CARACTERES ? t.substring(0, MAX_CARACTERES) + "…" : t;
    }

    private Tool herramientaPantalla() {
        Map<String, Object> seccion = Map.of(
                "type", "object",
                "properties", Map.of(
                        "titulo", Map.of("type", "string",
                                "description", "Encabezado de la sección, p. ej. 'Ingredientes' o 'Pasos' (puede ir vacío)"),
                        "elementos", Map.of("type", "array", "items", Map.of("type", "string"),
                                "description", "Las líneas de la sección, cortas y claras (un ingrediente, un paso, un resultado...)"),
                        "numerada", Map.of("type", "boolean",
                                "description", "true para numerarlas (pasos de una receta), false para viñetas")),
                "required", List.of("titulo", "elementos", "numerada"),
                "additionalProperties", false);
        return Tool.builder()
                .name("mostrar_en_pantalla")
                .description("Muestra contenido en tu pantalla (sustituye a tus ojos hasta que la quites): un título y "
                        + "secciones con listas. Ideal para recetas, listas, horarios, resultados de búsquedas o la agenda.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("titulo", JsonValue.from(Map.of(
                                        "type", "string", "description", "Título grande, p. ej. 'Lentejas con chorizo'")))
                                .putAdditionalProperty("secciones", JsonValue.from(Map.of(
                                        "type", "array", "items", seccion,
                                        "description", "Las secciones, en orden (p. ej. Ingredientes y luego Pasos)")))
                                .putAdditionalProperty("fija", JsonValue.from(Map.of(
                                        "type", "boolean",
                                        "description", "true si la van a ir leyendo un rato (una receta mientras "
                                                + "cocinan): se queda hasta que la quites. false para lo demás (una "
                                                + "lista, la agenda, un resultado): se quita sola al acabar la conversación")))
                                .build())
                        .required(List.of("titulo", "secciones", "fija"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaQuitarPantalla() {
        return Tool.builder()
                .name("quitar_pantalla")
                .description("Quita lo que haya en tu pantalla y vuelve a mostrar tus ojos. Úsala cuando digan "
                        + "\"apaga la pantalla\", \"quita eso\", \"ya está\" o cuando lo mostrado ya no haga falta.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaRuedas() {
        return Tool.builder()
                .name("mover_ruedas")
                .description("Mueve las dos ruedas del robot. Cada llamada es un movimiento corto que se para solo "
                        + "al acabar la duración. Para secuencias (bailar, dar una vuelta...) haz varias llamadas: "
                        + "se ejecutan en cola, una detrás de otra. Como mucho " + maxMsMovimientoTurno / 1000
                        + " s de movimiento en total por respuesta.")
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

    private Tool herramientaCabeza() {
        return Tool.builder()
                .name("mover_cabeza")
                .description("Gira tu cabeza a un lado. Se queda mirando ahí hasta que la vuelvas a mover; "
                        + "vuelve al frente (0) cuando acabes. Para secuencias (decir que no, mirar a los dos "
                        + "lados) haz varias llamadas: se hacen en orden, junto con las ruedas.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("grados", JsonValue.from(Map.of(
                                        "type", "integer",
                                        "description", "Hacia dónde mirar: 0 al frente, positivo a tu izquierda, "
                                                + "negativo a tu derecha (como mucho " + GIRO_MAX_CABEZA + " a cada lado)")))
                                .build())
                        .required(List.of("grados"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaGesto() {
        return Tool.builder()
                .name("gesto_cabeza")
                .description("Hace un gesto con la cabeza: negar (decir que no) o mirar_alrededor. "
                        + "Al acabar, la cabeza vuelve al frente.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("gesto", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", List.of("negar", "mirar_alrededor"),
                                        "description", "Qué gesto hacer")))
                                .build())
                        .required(List.of("gesto"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaSeguir() {
        return Tool.builder()
                .name("seguir_mano")
                .description("Empieza o deja de seguir una mano con tus ojos de ultrasonidos (te quedas a unos "
                        + "20 cm, girando hacia ella). Para sola si la pierde unos segundos o al minuto.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("activar", JsonValue.from(Map.of(
                                        "type", "boolean",
                                        "description", "true para empezar a seguir, false para parar")))
                                .build())
                        .required(List.of("activar"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaDistancia() {
        return Tool.builder()
                .name("medir_distancia")
                .description("Mide con tus ojos de ultrasonidos a cuántos centímetros está lo que tienes delante, "
                        + "hacia donde mira tu cabeza (espera a que acaben los movimientos pedidos antes). "
                        + "Alcance de unos 4 metros; no dice qué es el objeto.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
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
                        + "aunque se reinicie tu cerebro. Un dato por llamada, en su sección.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("seccion", JsonValue.from(Map.of(
                                        "type", "string",
                                        "enum", MemoriaLargoPlazo.SECCIONES,
                                        "description", "Dónde apuntarlo")))
                                .putAdditionalProperty("dato", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "El dato, como una frase corta en tercera persona")))
                                .build())
                        .required(List.of("seccion", "dato"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaCorregirRecuerdo() {
        return Tool.builder()
                .name("corregir_recuerdo")
                .description("Cambia un dato de tu memoria que ya no es correcto por su versión nueva. Solo lo "
                        + "cambia si encaja exactamente un recuerdo con lo que buscas.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("buscar", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Un trozo del recuerdo que hay que cambiar, tal como aparece en tu memoria")))
                                .putAdditionalProperty("nuevo", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "El dato correcto, como una frase corta en tercera persona")))
                                .build())
                        .required(List.of("buscar", "nuevo"))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private Tool herramientaOlvidar() {
        return Tool.builder()
                .name("olvidar")
                .description("Borra un dato de tu memoria que ya no es verdad o ya no hace falta (una compra ya "
                        + "hecha, una tarea terminada...). Solo lo borra si encaja exactamente un recuerdo.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("buscar", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Un trozo del recuerdo que hay que borrar, tal como aparece en tu memoria")))
                                .build())
                        .required(List.of("buscar"))
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
            int siguiente = inicio + 1;
            while (siguiente < mensajes.size() && !iniciosDeTurno.contains(mensajes.get(siguiente))) {
                siguiente++;
            }
            if (siguiente >= mensajes.size()) {
                break; // el último turno es muy largo (p. ej. un baile): se guarda entero
            }
            inicio = siguiente;
        }
        for (int i = 0; i < inicio; i++) {
            iniciosDeTurno.remove(mensajes.get(i));
        }
        historial.clear();
        historial.addAll(mensajes.subList(inicio, mensajes.size()));
    }

    /** Crea el mensaje con lo que dice el usuario y lo apunta como principio de un turno. */
    private MessageParam mensajeDeUsuario(String texto) {
        MessageParam mensaje = MessageParam.builder().role(MessageParam.Role.USER).content(texto).build();
        iniciosDeTurno.add(mensaje);
        return mensaje;
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
