package org.jarvis.smarthome.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.PublishSubscribeChannel;
import org.springframework.messaging.MessageChannel;

/**
 * Spring Integration configuration.
 * Explicitly declares errorChannel to avoid "No bean named 'errorChannel'"
 * warning.
 */
@Configuration
public class IntegrationConfig {

    /**
     * Explicitly declare errorChannel for Spring Integration.
     * This prevents the framework from creating a default channel and showing a
     * warning.
     */
    @Bean
    public MessageChannel errorChannel() {
        return new PublishSubscribeChannel();
    }
}
