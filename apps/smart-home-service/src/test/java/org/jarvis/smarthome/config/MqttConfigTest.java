package org.jarvis.smarthome.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttConfigTest {

    private MqttConfig config;

    @BeforeEach
    void setUp() {
        config = new MqttConfig();
        ReflectionTestUtils.setField(config, "brokerUrl", "tcp://localhost:1883");
        ReflectionTestUtils.setField(config, "clientId", "jarvis-smart-home");
        ReflectionTestUtils.setField(config, "username", "");
        ReflectionTestUtils.setField(config, "password", "");
    }

    @Test
    void mqttClientFactoryConfiguresBrokerUrlWithoutCredentialsWhenBlank() {
        MqttPahoClientFactory factory = config.mqttClientFactory();

        MqttConnectOptions options = factory.getConnectionOptions();
        assertArrayEquals(new String[]{"tcp://localhost:1883"}, options.getServerURIs());
        assertTrue(options.isCleanSession());
        assertNull(options.getUserName());
        assertNull(options.getPassword());
    }

    @Test
    void mqttClientFactoryAppliesUsernameAndPasswordWhenPresent() {
        ReflectionTestUtils.setField(config, "username", "user");
        ReflectionTestUtils.setField(config, "password", "secret");

        MqttConnectOptions options = config.mqttClientFactory().getConnectionOptions();

        assertTrue("user".equals(options.getUserName()));
        assertArrayEquals("secret".toCharArray(), options.getPassword());
    }

    @Test
    void mqttClientFactoryUsesCustomBrokerUrl() {
        ReflectionTestUtils.setField(config, "brokerUrl", "tcp://broker.example.com:1883");

        MqttConnectOptions options = config.mqttClientFactory().getConnectionOptions();

        assertArrayEquals(new String[]{"tcp://broker.example.com:1883"}, options.getServerURIs());
    }

    @Test
    void mqttOutboundChannelIsDirectChannel() {
        MessageChannel channel = config.mqttOutboundChannel();

        assertTrue(channel instanceof DirectChannel);
    }

    @Test
    void mqttOutboundCreatesMessageHandler() {
        MessageHandler handler = config.mqttOutbound();

        assertTrue(handler instanceof MqttPahoMessageHandler);
    }
}
