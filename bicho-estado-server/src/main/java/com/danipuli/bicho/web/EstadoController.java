package com.danipuli.bicho.web;

import com.danipuli.bicho.model.Estado;
import com.danipuli.bicho.model.EstadoMensaje;
import com.danipuli.bicho.ws.EstadoWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint REST de apoyo al WebSocket. Sirve sobre todo para probar sin tener
 * que escribir un cliente WebSocket: basta con un curl o Postman.
 *
 * Ejemplo:
 *   curl -X POST http://localhost:8080/api/estado \
 *        -H "Content-Type: application/json" \
 *        -d '{"estado":"hablando","texto":"Hola Daniel, aquí estoy"}'
 *
 * Cuando algún día algo (la ESP32 con firmware propio, un backend intermedio, etc.)
 * sepa de verdad cuándo el bicho está escuchando o hablando, puede llamar a este
 * mismo endpoint en vez de hablar WebSocket directamente.
 */
@RestController
public class EstadoController {

    private final EstadoWebSocketHandler estadoWebSocketHandler;

    @Autowired
    public EstadoController(EstadoWebSocketHandler estadoWebSocketHandler) {
        this.estadoWebSocketHandler = estadoWebSocketHandler;
    }

    @GetMapping("/api/estado")
    public EstadoMensaje verEstadoActual() {
        return EstadoMensaje.de(estadoWebSocketHandler.getEstadoActual(), estadoWebSocketHandler.getTextoActual());
    }

    @PostMapping("/api/estado")
    public ResponseEntity<EstadoMensaje> cambiarEstado(@RequestBody EstadoMensaje entrante) {
        Estado nuevoEstado = Estado.desdeTexto(entrante.getEstado());
        estadoWebSocketHandler.cambiarEstado(nuevoEstado, entrante.getTexto());
        return ResponseEntity.ok(EstadoMensaje.de(nuevoEstado, entrante.getTexto()));
    }
}
