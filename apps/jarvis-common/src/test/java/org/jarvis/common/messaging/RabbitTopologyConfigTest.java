package org.jarvis.common.messaging;

import org.jarvis.commands.CommandTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RabbitTopologyConfigTest {

    private final RabbitTopologyConfig config = new RabbitTopologyConfig();

    @Test
    void dlxExchangesAreDurableTopicExchanges() {
        TopicExchange commandsDlx = config.jarvisDlxCommandsExchange();
        TopicExchange tasksDlx = config.jarvisDlxTasksExchange();

        assertEquals(CommandTopology.DLX_COMMANDS, commandsDlx.getName());
        assertTrue(commandsDlx.isDurable());
        assertFalse(commandsDlx.isAutoDelete());
        assertEquals(CommandTopology.DLX_TASKS, tasksDlx.getName());
        assertTrue(tasksDlx.isDurable());
    }

    @Test
    void dlqQueuesAreDurable() {
        Queue commandsDlq = config.jarvisDlqCommandsQueue();
        Queue tasksDlq = config.jarvisDlqTasksQueue();

        assertEquals(CommandTopology.QUEUE_DLQ_COMMANDS, commandsDlq.getName());
        assertTrue(commandsDlq.isDurable());
        assertEquals(CommandTopology.QUEUE_DLQ_TASKS, tasksDlq.getName());
        assertTrue(tasksDlq.isDurable());
    }

    @Test
    void dlqBindingsCatchAllRoutingKeys() {
        Binding commandsBinding = config.bindDlqCommands(
                config.jarvisDlqCommandsQueue(), config.jarvisDlxCommandsExchange());
        Binding tasksBinding = config.bindDlqTasks(
                config.jarvisDlqTasksQueue(), config.jarvisDlxTasksExchange());

        assertEquals("#", commandsBinding.getRoutingKey());
        assertEquals(CommandTopology.QUEUE_DLQ_COMMANDS, commandsBinding.getDestination());
        assertEquals(CommandTopology.DLX_COMMANDS, commandsBinding.getExchange());

        assertEquals("#", tasksBinding.getRoutingKey());
        assertEquals(CommandTopology.QUEUE_DLQ_TASKS, tasksBinding.getDestination());
        assertEquals(CommandTopology.DLX_TASKS, tasksBinding.getExchange());
    }

    @Test
    void commandQueuesRouteDeadLettersToCommandsDlx() {
        Queue execute = config.jarvisAgentExecuteQueue();
        Queue result = config.jarvisAgentResultQueue();
        Queue confirmationRequest = config.jarvisConfirmationRequestQueue();
        Queue confirmationResult = config.jarvisConfirmationResultQueue();

        assertEquals(CommandTopology.QUEUE_AGENT_EXECUTE, execute.getName());
        assertEquals(CommandTopology.DLX_COMMANDS, execute.getArguments().get("x-dead-letter-exchange"));
        assertEquals(CommandTopology.QUEUE_AGENT_EXECUTE, execute.getArguments().get("x-dead-letter-routing-key"));

        assertEquals(CommandTopology.QUEUE_AGENT_RESULT, result.getName());
        assertEquals(CommandTopology.QUEUE_CONFIRMATION_REQUEST, confirmationRequest.getName());
        assertEquals(CommandTopology.QUEUE_CONFIRMATION_RESULT, confirmationResult.getName());
        assertTrue(execute.isDurable());
    }

    @Test
    void taskQueuesRouteDeadLettersToTasksDlx() {
        Queue background = config.jarvisTasksBackgroundQueue();
        Queue llm = config.jarvisTasksLlmQueue();

        assertEquals(CommandTopology.QUEUE_TASKS_BACKGROUND, background.getName());
        assertEquals(CommandTopology.DLX_TASKS, background.getArguments().get("x-dead-letter-exchange"));
        assertEquals(CommandTopology.QUEUE_TASKS_BACKGROUND, background.getArguments().get("x-dead-letter-routing-key"));

        assertEquals(CommandTopology.QUEUE_TASKS_LLM, llm.getName());
        assertEquals(CommandTopology.DLX_TASKS, llm.getArguments().get("x-dead-letter-exchange"));
    }

    @Test
    void jsonMessageConverterIsCreated() {
        MessageConverter converter = config.jarvisJsonMessageConverter();

        assertNotNull(converter);
    }

    @Test
    void rabbitTemplateUsesProvidedConnectionFactoryAndConverter() {
        ConnectionFactory connectionFactory = new FakeConnectionFactory();
        MessageConverter converter = config.jarvisJsonMessageConverter();

        RabbitTemplate template = config.jarvisRabbitTemplate(connectionFactory, converter);

        assertSame(converter, template.getMessageConverter());
        assertSame(connectionFactory, template.getConnectionFactory());
    }

    private static final class FakeConnectionFactory implements ConnectionFactory {
        @Override
        public Connection createConnection() {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public String getHost() {
            return "localhost";
        }

        @Override
        public int getPort() {
            return 5672;
        }

        @Override
        public String getVirtualHost() {
            return "/";
        }

        @Override
        public String getUsername() {
            return "guest";
        }

        @Override
        public void addConnectionListener(ConnectionListener listener) {
            // no-op
        }

        @Override
        public boolean removeConnectionListener(ConnectionListener listener) {
            return false;
        }

        @Override
        public void clearConnectionListeners() {
            // no-op
        }
    }
}
