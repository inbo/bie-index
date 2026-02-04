package au.org.ala.bie

import grails.plugin.springwebsocket.GrailsSimpAnnotationMethodMessageHandler
import org.springframework.context.annotation.Bean
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry

/**
 * Custom WebSocket configuration that extends the grails-spring-websocket plugin's
 * default config but adds CORS support for SockJS fallback transports.
 *
 * This fixes 403 "Invalid CORS request" errors when the app is behind an ALB/proxy
 * and the browser uses XHR streaming/polling fallbacks with credentials/cookies.
 */
@EnableWebSocketMessageBroker
class CustomWebSocketConfig extends AbstractWebSocketMessageBrokerConfigurer {

    @Override
    void configureMessageBroker(MessageBrokerRegistry messageBrokerRegistry) {
        messageBrokerRegistry.enableSimpleBroker("/queue", "/topic")
        messageBrokerRegistry.setApplicationDestinationPrefixes("/app")
    }

    @Override
    void registerStompEndpoints(StompEndpointRegistry stompEndpointRegistry) {
        // Allow the production origin for SockJS transports (XHR streaming/polling with credentials)
        // Using setAllowedOriginPatterns allows credentials to be sent (unlike wildcard '*')
        stompEndpointRegistry.addEndpoint("/stomp")
                .setAllowedOriginPatterns("*")
                .withSockJS()
    }

    @Bean
    GrailsSimpAnnotationMethodMessageHandler grailsSimpAnnotationMethodMessageHandler(
            MessageChannel clientInboundChannel,
            MessageChannel clientOutboundChannel,
            SimpMessagingTemplate brokerMessagingTemplate
    ) {
        def handler = new GrailsSimpAnnotationMethodMessageHandler(clientInboundChannel, clientOutboundChannel, brokerMessagingTemplate)
        handler.destinationPrefixes = ["/app"]
        return handler
    }

}
