package com.example.agentflow.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the embedded HTTP server from the typed {@link AgentFlowProperties} so the bind interface
 * and port are configuration (and validated), never hardcoded in code (S01-17).
 */
@Configuration
class HttpServerConfiguration {

  private final AgentFlowProperties properties;

  HttpServerConfiguration(AgentFlowProperties properties) {
    this.properties = properties;
  }

  /**
   * @return a customizer that applies {@code agentflow.http.*} to the embedded servlet server
   */
  @Bean
  WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> httpServerCustomizer() {
    return factory -> {
      factory.setPort(properties.getHttp().getPort());
      // setAddress takes an InetAddress; an unparseable host fails boot explicitly
      // rather than binding to an unknown interface.
      try {
        factory.setAddress(InetAddress.getByName(properties.getHttp().getHost()));
      } catch (UnknownHostException e) {
        throw new IllegalStateException(
            "Invalid agentflow.http.host: " + properties.getHttp().getHost(), e);
      }
    };
  }
}
