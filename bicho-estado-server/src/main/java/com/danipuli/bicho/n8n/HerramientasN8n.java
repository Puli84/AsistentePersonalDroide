package com.danipuli.bicho.n8n;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Herramientas extra para Claude que son flujos de n8n (notas, mensajes, calendario...).
 *
 * Se definen en un fichero JSON (bicho.n8n.herramientas, por defecto herramientas-n8n.json en la
 * carpeta del proyecto) que se relee en cada conversación: para añadir una automatización basta con
 * crear el flujo en n8n (que empiece por un nodo Webhook) y añadir su entrada al fichero, sin
 * reiniciar. Formato:
 *
 * <pre>
 * [
 *   {
 *     "nombre": "apuntar_nota",
 *     "descripcion": "Apunta una nota en las notas de Daniel. Úsala cuando te pidan apuntar algo.",
 *     "url": "http://localhost:5678/webhook/robodragon-nota",
 *     "parametros": { "texto": "Lo que hay que apuntar, tal cual" },
 *     "confirmar": false
 *   }
 * ]
 * </pre>
 *
 * Cuando Claude la usa, se hace un POST al webhook con los parámetros en JSON y lo que conteste n8n
 * (texto o JSON) se le devuelve a Claude. Con "confirmar": true, Claude tiene que leer en voz alta
 * lo que va a hacer y esperar un sí antes de usarla (para mensajes, correos...).
 */
@Component
public class HerramientasN8n {

    private static final Logger log = LoggerFactory.getLogger(HerramientasN8n.class);

    /** Las herramientas propias del robot: una de n8n no puede llamarse igual. */
    private static final Set<String> NOMBRES_RESERVADOS =
            Set.of("mover_ruedas", "poner_cara", "recordar", "cambiar_volumen", "terminar_conversacion", "web_search");

    /** Lo máximo de la respuesta de n8n que se le pasa a Claude. */
    private static final int MAX_CARACTERES_RESPUESTA = 3000;

    private final Path fichero;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public HerramientasN8n(@Value("${bicho.n8n.herramientas:herramientas-n8n.json}") String fichero) {
        this.fichero = Path.of(fichero).toAbsolutePath();
        log.info("Herramientas de n8n: {}{}", this.fichero,
                Files.exists(this.fichero) ? "" : " (no existe: sin herramientas de n8n)");
    }

    /** Una herramienta tal como viene en el fichero. */
    public record Definicion(String nombre, String descripcion, String url, Map<String, String> parametros,
                             boolean confirmar) {
    }

    /** Lee el fichero (en cada turno, para poder editarlo sin reiniciar). Las entradas mal escritas se saltan. */
    public List<Definicion> leer() {
        String json;
        try {
            json = Files.readString(fichero, StandardCharsets.UTF_8);
        } catch (NoSuchFileException ex) {
            return List.of();
        } catch (IOException ex) {
            log.warn("No se pudo leer {}: {}", fichero, ex.getMessage());
            return List.of();
        }
        List<Map<String, Object>> entradas;
        try {
            entradas = mapper.readValue(json, new TypeReference<>() {
            });
        } catch (IOException ex) {
            log.warn("{} no es un JSON válido: {}", fichero, ex.getMessage());
            return List.of();
        }
        List<Definicion> definiciones = new ArrayList<>();
        for (Map<String, Object> e : entradas) {
            String nombre = String.valueOf(e.get("nombre"));
            String url = String.valueOf(e.get("url"));
            if (!nombre.matches("[a-zA-Z0-9_-]{1,64}") || NOMBRES_RESERVADOS.contains(nombre)
                    || !url.startsWith("http")) {
                log.warn("Herramienta de n8n mal definida, la salto: {}", e);
                continue;
            }
            Map<String, String> parametros = new LinkedHashMap<>();
            if (e.get("parametros") instanceof Map<?, ?> p) {
                p.forEach((k, v) -> parametros.put(String.valueOf(k), String.valueOf(v)));
            }
            definiciones.add(new Definicion(nombre, String.valueOf(e.getOrDefault("descripcion", "")), url,
                    parametros, Boolean.TRUE.equals(e.get("confirmar"))));
        }
        return definiciones;
    }

    /** La herramienta en el formato que entiende Claude. Todos los parámetros son texto y obligatorios. */
    public static Tool aTool(Definicion d) {
        Tool.InputSchema.Properties.Builder propiedades = Tool.InputSchema.Properties.builder();
        d.parametros().forEach((nombre, descripcion) -> propiedades.putAdditionalProperty(nombre,
                JsonValue.from(Map.of("type", "string", "description", descripcion))));
        String descripcion = d.descripcion() + (d.confirmar()
                ? " IMPORTANTE: antes de usarla, di en voz alta exactamente qué vas a hacer (a quién y qué) y"
                + " pregunta si lo haces; úsala solo cuando te respondan que sí."
                : "");
        return Tool.builder()
                .name(d.nombre())
                .description(descripcion)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(propiedades.build())
                        .required(List.copyOf(d.parametros().keySet()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    /**
     * Llama al webhook de n8n con lo que ha pedido Claude.
     *
     * @return lo que contesta n8n, o un texto de error para que Claude lo cuente
     */
    public String ejecutar(Definicion d, Map<String, Object> entrada) {
        try {
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(d.url()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(entrada), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String cuerpo = respuesta.body() == null ? "" : respuesta.body().strip();
            if (cuerpo.length() > MAX_CARACTERES_RESPUESTA) {
                cuerpo = cuerpo.substring(0, MAX_CARACTERES_RESPUESTA) + "…";
            }
            log.info("n8n {} → {}: {}", d.nombre(), respuesta.statusCode(), cuerpo);
            if (respuesta.statusCode() >= 400) {
                return "Error: la automatización " + d.nombre() + " ha fallado (n8n respondió "
                        + respuesta.statusCode() + "). " + cuerpo;
            }
            return cuerpo.isEmpty() ? "Hecho." : "Hecho. Respuesta: " + cuerpo;
        } catch (IOException ex) {
            log.warn("No se pudo llamar a n8n ({}): {}", d.url(), ex.toString());
            return "Error: no se puede hablar con n8n (¿está arrancado?).";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "Error: interrumpido";
        }
    }
}
