package com.danipuli.bicho.cerebro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * La memoria a largo plazo del bicho: un fichero de texto (memoria.txt) que se puede editar a mano,
 * organizado por secciones:
 *
 * <pre>
 * # Leo
 * - Leo es del Barça.
 *
 * # Pendiente
 * - Hay que comprar papel higiénico
 * </pre>
 *
 * Claude puede apuntar un dato en una sección, corregirlo u olvidarlo. Para corregir y olvidar se
 * busca por un trozo del texto (sin mayúsculas ni tildes) y solo se toca si encaja exactamente uno,
 * para no borrar lo que no es.
 */
public class MemoriaLargoPlazo {

    private static final Logger log = LoggerFactory.getLogger(MemoriaLargoPlazo.class);

    /** Las secciones que puede usar Claude, en este orden. A mano se pueden añadir otras. */
    public static final List<String> SECCIONES =
            List.of("Daniel", "Leo", "Familia", "Casa", "Pendiente", "RoboDragón", "Otros");

    private final Path fichero;

    public MemoriaLargoPlazo(Path fichero) {
        this.fichero = fichero;
    }

    public Path fichero() {
        return fichero;
    }

    /** Todo el contenido, tal cual (para meterlo en el prompt), o "" si está vacía. */
    public synchronized String contenido() {
        List<String> lineas = leer();
        // Sin las secciones vacías, que no le dicen nada a Claude
        List<String> util = new ArrayList<>();
        for (int i = 0; i < lineas.size(); i++) {
            String l = lineas.get(i);
            if (esCabecera(l) && !tieneElementos(lineas, i)) {
                continue;
            }
            if (!l.isBlank()) {
                util.add(l);
            }
        }
        return String.join("\n", util).strip();
    }

    /** Apunta un dato en su sección (la crea si no existe). */
    public synchronized String recordar(String seccion, String dato) {
        String limpio = dato == null ? "" : dato.strip().replaceAll("\\s+", " ");
        if (limpio.isEmpty() || limpio.equals("null")) {
            return "Error: no hay nada que recordar";
        }
        String nombre = seccionValida(seccion);
        List<String> lineas = leer();
        for (String l : lineas) {
            if (esElemento(l) && normalizar(textoDe(l)).equals(normalizar(limpio))) {
                return "Eso ya lo tenías apuntado.";
            }
        }
        int cabecera = buscarCabecera(lineas, nombre);
        if (cabecera < 0) {
            if (!lineas.isEmpty() && !lineas.get(lineas.size() - 1).isBlank()) {
                lineas.add("");
            }
            lineas.add("# " + nombre);
            lineas.add("- " + limpio);
        } else {
            lineas.add(finDeSeccion(lineas, cabecera), "- " + limpio);
        }
        escribir(lineas);
        log.info("Nuevo recuerdo en {}: {}", nombre, limpio);
        return "Apuntado en tu memoria (" + nombre + "): " + limpio;
    }

    /** Cambia el recuerdo que contiene {@code buscar} por {@code nuevo}. */
    public synchronized String corregir(String buscar, String nuevo) {
        String limpio = nuevo == null ? "" : nuevo.strip().replaceAll("\\s+", " ");
        if (limpio.isEmpty()) {
            return "Error: falta el dato nuevo";
        }
        List<String> lineas = leer();
        List<Integer> encajan = buscarElementos(lineas, buscar);
        if (encajan.size() != 1) {
            return explicarBusqueda(lineas, encajan, buscar, "corregido");
        }
        int i = encajan.get(0);
        String antes = textoDe(lineas.get(i));
        lineas.set(i, "- " + limpio);
        escribir(lineas);
        log.info("Recuerdo corregido: '{}' → '{}'", antes, limpio);
        return "Corregido: antes ponía \"" + antes + "\", ahora \"" + limpio + "\".";
    }

    /** Borra el recuerdo que contiene {@code buscar}. */
    public synchronized String olvidar(String buscar) {
        List<String> lineas = leer();
        List<Integer> encajan = buscarElementos(lineas, buscar);
        if (encajan.size() != 1) {
            return explicarBusqueda(lineas, encajan, buscar, "olvidado");
        }
        String antes = textoDe(lineas.remove((int) encajan.get(0)));
        escribir(lineas);
        log.info("Recuerdo olvidado: {}", antes);
        return "Olvidado: " + antes;
    }

    // ------------------------------------------------------------------ utilidades

    private String explicarBusqueda(List<String> lineas, List<Integer> encajan, String buscar, String accion) {
        if (buscar == null || normalizar(buscar).isEmpty()) {
            return "Error: no he " + accion + " nada, falta decir qué recuerdo.";
        }
        if (encajan.isEmpty()) {
            return "No he " + accion + " nada: no encuentro ningún recuerdo con \"" + buscar + "\".";
        }
        return "No he " + accion + " nada: encajan varios, sé más concreto: "
                + encajan.stream().map(i -> "\"" + textoDe(lineas.get(i)) + "\"").collect(Collectors.joining("; "));
    }

    private static List<Integer> buscarElementos(List<String> lineas, String buscar) {
        List<Integer> encajan = new ArrayList<>();
        String b = normalizar(buscar);
        if (b.isEmpty()) {
            return encajan;
        }
        for (int i = 0; i < lineas.size(); i++) {
            if (esElemento(lineas.get(i)) && normalizar(textoDe(lineas.get(i))).contains(b)) {
                encajan.add(i);
            }
        }
        return encajan;
    }

    /** La sección que pide Claude, o "Otros" si no es ninguna conocida (sin importar tildes ni mayúsculas). */
    static String seccionValida(String seccion) {
        String s = normalizar(seccion);
        return SECCIONES.stream().filter(n -> normalizar(n).equals(s)).findFirst().orElse("Otros");
    }

    private static int buscarCabecera(List<String> lineas, String nombre) {
        for (int i = 0; i < lineas.size(); i++) {
            if (esCabecera(lineas.get(i)) && normalizar(lineas.get(i).substring(1)).equals(normalizar(nombre))) {
                return i;
            }
        }
        return -1;
    }

    /** Dónde insertar un elemento nuevo: justo después del último de la sección. */
    private static int finDeSeccion(List<String> lineas, int cabecera) {
        int fin = cabecera + 1;
        for (int i = cabecera + 1; i < lineas.size() && !esCabecera(lineas.get(i)); i++) {
            if (!lineas.get(i).isBlank()) {
                fin = i + 1;
            }
        }
        return fin;
    }

    private static boolean tieneElementos(List<String> lineas, int cabecera) {
        for (int i = cabecera + 1; i < lineas.size() && !esCabecera(lineas.get(i)); i++) {
            if (!lineas.get(i).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean esCabecera(String linea) {
        return linea.startsWith("#");
    }

    /** Un recuerdo: cualquier línea con texto que no sea una cabecera (normalmente empieza por "- "). */
    private static boolean esElemento(String linea) {
        return !linea.isBlank() && !esCabecera(linea);
    }

    private static String textoDe(String linea) {
        String t = linea.strip();
        return t.startsWith("-") ? t.substring(1).strip() : t;
    }

    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        return Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").strip();
    }

    private List<String> leer() {
        try {
            return new ArrayList<>(Files.readAllLines(fichero, StandardCharsets.UTF_8));
        } catch (NoSuchFileException ex) {
            return new ArrayList<>();
        } catch (IOException ex) {
            log.warn("No se pudo leer {}: {}", fichero, ex.getMessage());
            return new ArrayList<>();
        }
    }

    private void escribir(List<String> lineas) {
        try {
            Files.writeString(fichero, String.join(System.lineSeparator(), lineas) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("No se pudo escribir en {}: {}", fichero, ex.getMessage());
        }
    }
}
