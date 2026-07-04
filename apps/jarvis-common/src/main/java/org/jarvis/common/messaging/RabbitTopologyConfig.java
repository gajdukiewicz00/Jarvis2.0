package org.jarvis.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jarvis.commands.CommandTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Phase 4 — declares the RabbitMQ topology for the Jarvis command pipeline.
 *
 * <p>Loaded automatically as a Spring Boot AutoConfiguration in any service
 * that pulls in {@code spring-boot-starter-amqp} AND has
 * {@code spring.rabbitmq.host} configured. Idempotent: queues are
 * declared with the same names and arguments regardless of which service
 * creates them.</p>
 *
 * <p>Topology summary:</p>
 * <ul>
 *   <li>4 command queues: agent.execute, agent.result,
 *       confirmation.request, confirmation.result.</li>
 *   <li>2 task queues: tasks.background, tasks.llm.</li>
 *   <li>2 dead-letter exchanges (commands, tasks) each with a queue that
 *       captures messages that timed out, were rejected, or got NACKed.</li>
 *   <li>The 4 command queues route to {@code jarvis.dlx.commands}; the 2
 *       task queues route to {@code jarvis.dlx.tasks}.</li>
 * </ul>
 *
 * <p>Per-message TTL is set by the publisher via the {@code expiration}
 * property in the AMQP message; this config does not impose a queue-wide
 * TTL so that voice commands (~30s) and background tasks (minutes) can
 * coexist on the same broker.</p>
 */
@AutoConfiguration(after = RabbitAutoConfiguration.class)
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(prefix = "spring.rabbitmq", name = "host")
@ConditionalOnBean(ConnectionFactory.class)
public class RabbitTopologyConfig {

    // ---------------------------------------------------------------------
    // Dead-letter exchanges + queues
    // ---------------------------------------------------------------------
    @Bean
    public TopicExchange jarvisDlxCommandsExchange() {
        return new TopicExchange(CommandTopology.DLX_COMMANDS, true, false);
    }

    @Bean
    public TopicExchange jarvisDlxTasksExchange() {
        return new TopicExchange(CommandTopology.DLX_TASKS, true, false);
    }

    @Bean
    public Queue jarvisDlqCommandsQueue() {
        return QueueBuilder.durable(CommandTopology.QUEUE_DLQ_COMMANDS).build();
    }

    @Bean
    public Queue jarvisDlqTasksQueue() {
        return QueueBuilder.durable(CommandTopology.QUEUE_DLQ_TASKS).build();
    }

    @Bean
    public Binding bindDlqCommands(Queue jarvisDlqCommandsQueue,
                                   TopicExchange jarvisDlxCommandsExchange) {
        return BindingBuilder.bind(jarvisDlqCommandsQueue)
                .to(jarvisDlxCommandsExchange)
                .with("#");
    }

    @Bean
    public Binding bindDlqTasks(Queue jarvisDlqTasksQueue,
                                TopicExchange jarvisDlxTasksExchange) {
        return BindingBuilder.bind(jarvisDlqTasksQueue)
                .to(jarvisDlxTasksExchange)
                .with("#");
    }

    // ---------------------------------------------------------------------
    // Command queues — DLX -> jarvis.dlx.commands
    // ---------------------------------------------------------------------
    @Bean
    public Queue jarvisAgentExecuteQueue() {
        return commandQueue(CommandTopology.QUEUE_AGENT_EXECUTE);
    }

    @Bean
    public Queue jarvisAgentResultQueue() {
        return commandQueue(CommandTopology.QUEUE_AGENT_RESULT);
    }

    @Bean
    public Queue jarvisConfirmationRequestQueue() {
        return commandQueue(CommandTopology.QUEUE_CONFIRMATION_REQUEST);
    }

    @Bean
    public Queue jarvisConfirmationResultQueue() {
        return commandQueue(CommandTopology.QUEUE_CONFIRMATION_RESULT);
    }

    private Queue commandQueue(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", CommandTopology.DLX_COMMANDS)
                .withArgument("x-dead-letter-routing-key", name)
                .build();
    }

    // ---------------------------------------------------------------------
    // Task queues — DLX -> jarvis.dlx.tasks
    // ---------------------------------------------------------------------
    @Bean
    public Queue jarvisTasksBackgroundQueue() {
        return taskQueue(CommandTopology.QUEUE_TASKS_BACKGROUND);
    }

    @Bean
    public Queue jarvisTasksLlmQueue() {
        return taskQueue(CommandTopology.QUEUE_TASKS_LLM);
    }

    private Queue taskQueue(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-dead-letter-exchange", CommandTopology.DLX_TASKS)
                .withArgument("x-dead-letter-routing-key", name)
                .build();
    }

    // ---------------------------------------------------------------------
    // Message converter — JSON for cross-service contract.
    // ---------------------------------------------------------------------
    @Bean
    @ConditionalOnMissingBean(MessageConverter.class)
    public MessageConverter jarvisJsonMessageConverter() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(RabbitTemplate.class)
    public RabbitTemplate jarvisRabbitTemplate(ConnectionFactory connectionFactory,
                                               MessageConverter jarvisJsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jarvisJsonMessageConverter);
        return template;
    }
}
