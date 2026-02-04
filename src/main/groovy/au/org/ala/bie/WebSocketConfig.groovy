package au.org.ala.bie

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * Registers the STOMP endpoint and allows cross-origin requests for SockJS transports.
 *
 * Fixes 403 "Invalid CORS request" seen when SockJS fallback (XHR streaming/polling)
 * attempts to connect through a fronting ALB/Proxy. Adjust allowed origins to a
 * more restrictive value in production if needed.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    void registerStompEndpoints(StompEndpointRegistry registry) {
        // Allow connections from any origin. Change to explicit origin(s) for tighter security.
        registry.addEndpoint('/stomp')
                .setAllowedOrigins('*')
                .withSockJS()
    }

}
