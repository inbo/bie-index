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
 * Custom WebSocket configurationthat adds CORS support for SockJS transports.
 */
@EnableWebSocketMessageBroker
class CustomWebSocketConfig extends AbstractWebSocketMessageBrokerConfigurer {

    @Override
    void registerStompEndpoints(StompEndpointRegistry stompEndpointRegistry) {
        def allowedOrigins = System.getProperty("websocket.cors.allowedOrigins", "*")
        stompEndpointRegistry.addEndpoint("/stomp")
                .setAllowedOriginPatterns("*")
                .withSockJS()
    }

}
