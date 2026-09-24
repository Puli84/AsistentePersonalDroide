package com.danipuli.bicho.config;

import com.danipuli.bicho.ws.EstadoWebSocketHandler;
import com.danipuli.bicho.ws.RobotWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EstadoWebSocketHandler estadoWebSocketHandler;
    private final RobotWebSocketHandler robotWebSocketHandler;

    @Autowired
    public WebSocketConfig(EstadoWebSocketHandler estadoWebSocketHandler,
                           RobotWebSocketHandler robotWebSocketHandler) {
        this.estadoWebSocketHandler = estadoWebSocketHandler;
        this.robotWebSocketHandler = robotWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // setAllowedOrigins("*") de momento para poder probar desde cualquier sitio
        // (la web de la cara publicada como artefacto, un cliente local, etc.).
        // Cuando esto pase a producción de verdad, conviene restringirlo.
        registry.addHandler(estadoWebSocketHandler, "/ws/estado")
                .setAllowedOrigins("*");

        // Comandos físicos (ruedas, servos) hacia la ESP32 — ver RobotWebSocketHandler
        registry.addHandler(robotWebSocketHandler, "/ws/robot")
                .setAllowedOrigins("*");
    }

    /** Deja pasar trozos de audio de hasta 64 KB por WebSocket (por defecto son 8 KB). */
    @Bean
    public ServletServerContainerFactoryBean contenedorWebSocket() {
        ServletServerContainerFactoryBean contenedor = new ServletServerContainerFactoryBean();
        contenedor.setMaxBinaryMessageBufferSize(64 * 1024);
        contenedor.setMaxTextMessageBufferSize(64 * 1024);
        return contenedor;
    }
}
