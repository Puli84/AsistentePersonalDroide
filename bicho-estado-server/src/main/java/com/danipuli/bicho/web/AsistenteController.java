package com.danipuli.bicho.web;

import com.danipuli.bicho.cerebro.AsistenteService;
import com.danipuli.bicho.cerebro.CerebroClaude;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conversar con el bicho desde la web:
 *
 *   POST /api/conversar       {"texto":"hola"}         → le escribes
 *   POST /api/conversar/voz   multipart "audio"        → le hablas con el micro del PC
 *   POST /api/olvidar                                   → empieza la conversación de cero
 *
 * Respuesta:
 *   { "tuTexto": "...", "texto": "lo que dice el bicho", "acciones": [...], "audio": "<mp3 en base64>" }
 */
@RestController
public class AsistenteController {

    private final AsistenteService asistente;
    private final CerebroClaude cerebro;

    public AsistenteController(AsistenteService asistente, CerebroClaude cerebro) {
        this.asistente = asistente;
        this.cerebro = cerebro;
    }

    @PostMapping("/api/conversar")
    public ResponseEntity<Map<String, Object>> conversar(@RequestBody Map<String, String> peticion) {
        String texto = peticion.getOrDefault("texto", "").trim();
        if (texto.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No has escrito nada"));
        }
        return aRespuesta(asistente.turnoDeTexto(texto, "mp3"));
    }

    @PostMapping("/api/conversar/voz")
    public ResponseEntity<Map<String, Object>> conversarPorVoz(@RequestParam("audio") MultipartFile audio)
            throws IOException {
        String nombre = audio.getOriginalFilename() == null ? "audio.webm" : audio.getOriginalFilename();
        return aRespuesta(asistente.turnoDeVoz(audio.getBytes(), nombre, "mp3"));
    }

    @PostMapping("/api/olvidar")
    public Map<String, Object> olvidar() {
        cerebro.olvidar();
        return Map.of("ok", true);
    }

    private static ResponseEntity<Map<String, Object>> aRespuesta(AsistenteService.Resultado r) {
        if (r.error() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", r.error()));
        }
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("tuTexto", r.tuTexto());
        cuerpo.put("texto", r.texto());
        cuerpo.put("acciones", r.acciones());
        if (r.audio() != null) {
            cuerpo.put("audio", Base64.getEncoder().encodeToString(r.audio()));
        }
        if (r.avisoVoz() != null) {
            cuerpo.put("avisoVoz", r.avisoVoz());
        }
        return ResponseEntity.ok(cuerpo);
    }
}
