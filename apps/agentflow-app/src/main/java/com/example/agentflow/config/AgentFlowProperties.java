package com.example.agentflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the AgentFlow artifact (S01-17).
 *
 * <p>Bound from the {@code agentflow.*} properties and validated at context startup through {@link
 * Validated} plus Bean Validation. A missing mandatory value (for example {@code
 * agentflow.app-name}) fails boot with a clear message instead of surfacing as a {@code
 * NullPointerException} later. The HTTP bind settings are driven here through a {@link
 * WebServerFactoryCustomizer} so the bind interface and port are configuration, never a hardcoded
 * address; the container default is {@code 0.0.0.0} (all interfaces), never {@code localhost}.
 */
@ConfigurationProperties(prefix = "agentflow")
@Validated
public class AgentFlowProperties {

  /**
   * Logical application identity. No default is given on purpose: a missing {@code
   * agentflow.app-name} must fail startup explicitly rather than boot with an unknown name.
   */
  @NotBlank(message = "agentflow.app-name is required and must not be blank")
  private String appName;

  @Valid private final Http http = new Http();
  @Valid private final Graph graph = new Graph();

  /**
   * @return the logical application name, used in logs and identity
   */
  public String getAppName() {
    return appName;
  }

  /**
   * @param appName the logical application name
   */
  public void setAppName(String appName) {
    this.appName = appName;
  }

  /**
   * @return the validated HTTP bind settings
   */
  public Http getHttp() {
    return http;
  }

  /**
   * @return the validated workflow graph settings
   */
  public Graph getGraph() {
    return graph;
  }

  /** HTTP server bind settings for this role. */
  public static class Http {

    /**
     * Bind port. {@code 0} selects an ephemeral port (used by the test profile); production values
     * are in {@code application-*.yml}. Validated against the IPv4 range.
     */
    @Min(value = 0, message = "agentflow.http.port must be between 0 and 65535 (0 = ephemeral)")
    @Max(value = 65535, message = "agentflow.http.port must be between 0 and 65535 (0 = ephemeral)")
    private Integer port = 8080;

    /**
     * Bind interface. {@code 0.0.0.0} (all interfaces) is the container default so the service is
     * reachable through the Kubernetes Service; never hardcode {@code localhost} here or in code.
     */
    @NotBlank(message = "agentflow.http.host is required and must not be blank")
    private String host = "0.0.0.0";

    /**
     * @param port the bind port (default {@code 8080}, or ephemeral when {@code 0})
     */
    public void setPort(Integer port) {
      this.port = port;
    }

    /**
     * @param host the bind interface (default {@code 0.0.0.0})
     */
    public void setHost(String host) {
      this.host = host;
    }

    /**
     * @return the bind port (default {@code 8080})
     */
    public Integer getPort() {
      return port;
    }

    /**
     * @return the bind interface (default {@code 0.0.0.0})
     */
    public String getHost() {
      return host;
    }
  }

  /** Workflow-graph settings. */
  public static class Graph {

    /**
     * Maximum serialized size, in bytes, of a workflow graph ({@code graph_json} payload) before
     * the persistence layer rejects it. Defaults to 1 MiB; a value below 1 fails startup. Set from
     * the environment (never hardcoded) so a runaway graph can never fill the database.
     */
    @Min(value = 1, message = "agentflow.workflow.graph.max-bytes must be at least 1")
    private int maxBytes = 1_000_000;

    /**
     * @return the maximum serialized graph size in bytes (default {@code 1000000}, 1 MiB)
     */
    public int getMaxBytes() {
      return maxBytes;
    }
  }
}
