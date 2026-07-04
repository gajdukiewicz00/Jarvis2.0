package org.jarvis.cloudrelay;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RelayProperties.class)
public class RelayConfig {
}
