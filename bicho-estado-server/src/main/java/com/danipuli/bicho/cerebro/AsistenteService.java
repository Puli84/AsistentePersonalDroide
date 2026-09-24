package com.danipuli.bicho.cerebro;

import com.danipuli.bicho.model.Estado;
import com.danipuli.bicho.voz.OpenAiVozClient;
import com.danipuli.bicho.ws.EstadoWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Un "turno" completo de conversación con el bicho, igual venga de la web o de la ESP32:
 *
 *   (audio → STT) → texto → Claude (+ herramientas) → respuesta → TTS → audio
 *
 * y mientras tanto va moviendo la cara: "escuchando" mientras piensa y "hablando" al contestar.
 */
@Service
public class AsistenteService {

    private static final Logger log = LoggerFactory.getLogger(AsistenteService.class);

    private final CerebroClaude cerebro;
    private final OpenAiVozClient voz;
    private final EstadoWebSocketHandler estado;

    public AsistenteService(CerebroClaude cerebro, OpenAiVozClient voz, EstadoWebSocketHandler estado) {
        this.cerebro = cerebro;
        this.voz = voz;
        this.estado = estado;
    }

    /**
     * Turno a partir de audio grabado.
     *
     * @param formatoAudio formato de la voz de respuesta: "mp3" (navegador) o "pcm" (ESP32)
     */
    public Resultado turnoDeVoz(byte[] audio, String nombreFichero, String formatoAudio) {
        estado.cambiarEstado(Estado.ESCUCHANDO, "");
        String texto;
        try {
            texto = voz.transcribir(audio, nombreFichero);
        } catch (Exception ex) {
            log.warn("Fallo al transcribir el audio", ex);
            estado.cambiarEstado(Estado.REPOSO, "");
            return Resultado.conError("No he podido entender el audio: " + ex.getMessage());
        }
        if (texto.isEmpty()) {
            estado.cambiarEstado(Estado.REPOSO, "");
            return Resultado.conError("No he oído nada");
        }
        return turnoDeTexto(texto, formatoAudio);
    }

    /** Turno a partir de texto (escrito, o ya transcrito). */
    public Resultado turnoDeTexto(String textoUsuario, String formatoAudio) {
        estado.cambiarEstado(Estado.ESCUCHANDO, "");

        CerebroClaude.Respuesta respuesta;
        try {
            respuesta = cerebro.conversar(textoUsuario);
        } catch (Exception ex) {
            log.warn("Fallo al hablar con Claude", ex);
            estado.cambiarEstado(Estado.REPOSO, "");
            return Resultado.conError("El cerebro ha fallado: " + ex.getMessage());
        }

        if (respuesta.texto().isEmpty()) {
            estado.cambiarEstado(Estado.REPOSO, "");
            return new Resultado(textoUsuario, "", respuesta.acciones(), null, null, null);
        }

        byte[] audio = null;
        String avisoVoz = null;
        try {
            audio = voz.sintetizar(respuesta.texto(), formatoAudio);
        } catch (Exception ex) {
            // Sin voz, pero la respuesta en texto sigue valiendo
            log.warn("Fallo al generar la voz", ex);
            avisoVoz = "Sin voz: " + ex.getMessage();
        }
        estado.cambiarEstado(Estado.HABLANDO, respuesta.texto());
        return new Resultado(textoUsuario, respuesta.texto(), respuesta.acciones(), audio, null, avisoVoz);
    }

    /**
     * Lo que ha pasado en un turno. Si {@code error} no es null, el turno ha fallado.
     * {@code audio} puede ser null (si no había nada que decir o falló la voz).
     */
    public record Resultado(String tuTexto,
                            String texto,
                            List<Map<String, Object>> acciones,
                            byte[] audio,
                            String error,
                            String avisoVoz) {

        static Resultado conError(String error) {
            return new Resultado(null, null, List.of(), null, error, null);
        }
    }
}
