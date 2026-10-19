package com.example.agentflow;

import com.example.agentflow.config.AgentFlowProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal Spring context used by {@link AgentFlowPropertiesStartupTest} to exercise the real
 * {@code @Validated} + {@code @ConfigurationProperties} startup validation path.
 */
@Configuration
@EnableConfigurationProperties(AgentFlowProperties.class)
public class AgentFlowStartupTestConfig {}
