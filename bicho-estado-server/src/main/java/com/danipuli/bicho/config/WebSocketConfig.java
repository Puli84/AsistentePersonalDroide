package com.danipuli.bicho.config;

import com.danipuli.bicho.ws.EstadoWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EstadoWebSocketHandler estadoWebSocketHandler;

    @Autowired
    public WebSocketConfig(EstadoWebSocketHandler estadoWebSocketHandler) {
        this.estadoWebSocketHandler = estadoWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // setAllowedOrigins("*") de momento para poder probar desde cualquier sitio
        // (la web de la cara publicada como artefacto, un cliente local, etc.).
        // Cuando esto pase a producción de verdad, conviene restringirlo.
        registry.addHandler(estadoWebSocketHandler, "/ws/estado")
                .setAllowedOrigins("*");
    }
}
