package com.sahithi.collab.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the relay endpoint.
 *
 * <p>Deliberately declares no beans of its own: it previously also defined {@link OperationCodec},
 * which the handler injects, so this class depended on the handler while the handler depended on
 * a bean this class produced - a startup-breaking cycle. The codec is now its own {@code @Component}.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EditorWebSocketHandler handler;

    public WebSocketConfig(EditorWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/editor").setAllowedOrigins("*");
    }
}
