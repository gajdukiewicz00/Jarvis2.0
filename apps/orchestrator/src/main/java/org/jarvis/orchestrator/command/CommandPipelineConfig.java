package org.jarvis.orchestrator.command;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 4 — turns on the RabbitMQ consumer plumbing and the scheduled sweeper
 * that times out lingering commands. Active only when the broker host is
 * configured (i.e. not in tests / profiles without RabbitMQ).
 */
@Configuration
@EnableRabbit
@EnableScheduling
@ConditionalOnProperty(prefix = "spring.rabbitmq", name = "host")
public class CommandPipelineConfig {
}
