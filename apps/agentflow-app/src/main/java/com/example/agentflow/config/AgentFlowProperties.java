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
  @Valid private final Security security = new Security();
  @Valid private final Outbox outbox = new Outbox();
  @Valid private final Inbox inbox = new Inbox();
  @Valid private final Kafka kafka = new Kafka();

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

  /**
   * @return the validated OAuth 2.0 resource server (JWT) settings
   */
  public Security getSecurity() {
    return security;
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

  /**
   * OAuth 2.0 resource server (JWT) settings (S04-03). Bound from {@code agentflow.security.*}. The
   * issuer is the trusted Keycloak realm that issues and signs the access tokens; the audience is
   * the {@code aud} the API expects (the {@code agentflow-api} OIDC client). Both are required: a
   * missing value fails boot explicitly. They are supplied per profile (see {@code
   * application-*.yml}) and come from the environment in the local and Kubernetes profiles.
   */
  public static class Security {

    /**
     * The trusted issuer (Keycloak realm URL) that signs the tokens. No default: must be supplied.
     */
    @NotBlank(message = "agentflow.security.issuer-uri is required and must not be blank")
    private String issuerUri;

    /** The expected audience ({@code aud}) claim of the tokens. No default: must be supplied. */
    @NotBlank(message = "agentflow.security.audience is required and must not be blank")
    private String audience;

    /**
     * @param issuerUri the trusted issuer (Keycloak realm URL) that signs the tokens
     */
    public void setIssuerUri(String issuerUri) {
      this.issuerUri = issuerUri;
    }

    /**
     * @return the trusted issuer (Keycloak realm URL)
     */
    public String getIssuerUri() {
      return issuerUri;
    }

    /**
     * @param audience the expected audience ({@code aud}) claim
     */
    public void setAudience(String audience) {
      this.audience = audience;
    }

    /**
     * @return the expected audience ({@code aud}) claim
     */
    public String getAudience() {
      return audience;
    }
  }

  /**
   * @return the validated outbox publisher settings
   */
  public Outbox getOutbox() {
    return outbox;
  }

  /**
   * @return the validated Kafka producer settings (S06-09)
   */
  public Kafka getKafka() {
    return kafka;
  }

  /**
   * Outbox publisher tuning (S06-07). Bound from {@code agentflow.outbox.*}; retry and batch limits
   * live in configuration, not in code (apps/agentflow-app AGENTS.md).
   */
  public static class Outbox {
    private final Publisher publisher = new Publisher();

    /**
     * @return the validated outbox publisher settings
     */
    public Publisher getPublisher() {
      return publisher;
    }

    /** Bounded-batch poll + bounded-retry backoff for the outbox publisher (S06-07). */
    public static class Publisher {
      /**
       * Maximum rows claimed per poll. Bounded so one poll publishes a limited batch and never
       * locks a burst of work onto a single publisher instance. Default 50.
       */
      @Min(value = 1, message = "agentflow.outbox.publisher.batch-size must be at least 1")
      private int batchSize = 50;

      /**
       * Maximum publish attempts before a row is held terminal for the DLQ ({@code S06-11}) instead
       * of retrying forever. Default 5.
       */
      @Min(value = 1, message = "agentflow.outbox.publisher.max-attempts must be at least 1")
      private int maxAttempts = 5;

      /** Base retry delay in seconds; the backoff grows exponentially from it (0 allowed). */
      @Min(
          value = 0,
          message = "agentflow.outbox.publisher.base-delay-seconds must be non-negative")
      private long baseDelaySeconds = 1;

      /** Maximum retry delay in seconds; the exponential backoff is capped at this value. */
      @Min(value = 1, message = "agentflow.outbox.publisher.max-delay-seconds must be at least 1")
      private long maxDelaySeconds = 60;

      /**
       * How long a {@code CLAIMED} row may remain un-acked before the poll treats it as a <b>stale
       * claim</b> — its publisher crashed after claiming but before recording the broker outcome
       * (the S06-08 crash window) — and re-publishes it. A live, slow publisher legitimately holds
       * a claim longer, so the bound is generous (default 300s). Re-publishing a row the broker
       * already delivered is a duplicate, not a loss (at-least-once, ADR-003 / S06-08).
       */
      @Min(
          value = 1,
          message = "agentflow.outbox.publisher.claim-timeout-seconds must be at least 1")
      private long claimTimeoutSeconds = 300;

      /**
       * @return the batch size bound (default {@code 50})
       */
      public int getBatchSize() {
        return batchSize;
      }

      /**
       * @return the maximum publish attempts before a permanent hold (default {@code 5})
       */
      public int getMaxAttempts() {
        return maxAttempts;
      }

      /**
       * @return the base retry delay in seconds (default {@code 1})
       */
      public long getBaseDelaySeconds() {
        return baseDelaySeconds;
      }

      /**
       * @return the capped maximum retry delay in seconds (default {@code 60})
       */
      public long getMaxDelaySeconds() {
        return maxDelaySeconds;
      }

      /**
       * @return the stale-claim timeout in seconds before a {@code CLAIMED} row is re-published
       *     (default {@code 300})
       */
      public long getClaimTimeoutSeconds() {
        return claimTimeoutSeconds;
      }
    }
  }

  /**
   * Kafka producer settings (S06-09). Bound from {@code agentflow.kafka.*}.
   *
   * <p>These configure the outbound producer that forwards a claimed {@code outbox_events} envelope
   * to the broker ({@link com.example.agentflow.messaging.worker.KafkaMessageDeliverer}). They are
   * the standard Kafka producer client settings, documented by Spring Kafka, tuned for
   * <b>reliability</b> rather than throughput:
   *
   * <ul>
   *   <li><b>acks=all</b> &mdash; the broker only confirms once every in-sync replica has the
   *       record, so a delivered envelope is durable before the publisher marks it {@code SENT}.
   *   <li><b>delivery-timeout</b> &mdash; the total time a send waits for the broker to confirm
   *       before the outcome is treated as <b>ambiguous</b> (the broker may or may not hold it) and
   *       the row is re-published at a bounded-backoff instant &mdash; a duplicate, never a loss
   *       (at-least-once, ADR-003).
   *   <li><b>retries + retry-backoff</b> &mdash; the producer retries a request-failed fault
   *       internally; the publisher applies its own <i>bounded</i> retry on top, so a stuck broker
   *       drains slowly instead of forever (S06-07).
   *   <li><b>batching</b> ({@code batch-size}, {@code linger-ms}, {@code buffer-memory}) &mdash;
   *       records are grouped for efficiency; the values are generous but bounded so a poll never
   *       buffers unbounded work.
   * </ul>
   *
   * <p><b>Not a substitute for the overall PostgreSQL/Kafka transaction (invariant 3).</b> Kafka
   * transactions or {@code enable.idempotence} are <b>not</b> used as the end-to-end safety net:
   * delivery is at-least-once, and correctness comes from the transactional outbox (ADR-004) plus
   * the consumer-side inbox dedup (S06-10), never from exactly-once Kafka processing.
   *
   * <p>The producer is disabled by default (the skeleton boots without Kafka, S01-02); enable it
   * with {@code agentflow.kafka.producer.enabled=true} and a reachable {@code bootstrap-servers} in
   * a profile (local / kubernetes). The bootstrap servers carry a local placeholder default only
   * for development; it must be overridden from the environment for any real deployment.
   */
  public static class Inbox {

    /**
     * The maximum number of <b>local attempts</b> a transient {@code InboxConsumerAction} failure
     * is retried within {@link com.example.agentflow.messaging.application.InboxDedupService}
     * before the row is marked {@code FAILED}. The first attempt counts as one; the default of 3
     * keeps a transient fault from failing immediately while staying bounded. The bounded retry
     * uses a single savepoint re-rolled before each attempt, so it needs at most one savepoint
     * regardless of this value.
     */
    private int maxAttempts = 3;

    /**
     * @return the maximum local attempts on a transient {@code InboxConsumerAction} failure (at
     *     least 1)
     */
    public int getMaxAttempts() {
      return maxAttempts;
    }

    /**
     * @param maxAttempts the maximum local attempts on a transient failure
     */
    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }
  }

  public static class Kafka {

    private final Producer producer = new Producer();

    /**
     * @return the validated Kafka producer settings
     */
    public Producer getProducer() {
      return producer;
    }

    /**
     * Producer client reliability tuning (S06-09). All values have safe defaults so a profile only
     * needs to set {@code enabled} and {@code bootstrap-servers} to start forwarding events.
     */
    public static class Producer {

      /**
       * Whether the outbound producer adapter is active. {@code false} by default so the skeleton
       * boots without Kafka (S01-02); set {@code true} in a profile once a reachable {@code
       * bootstrap-servers} is supplied (local / kubernetes). When false the outbox publisher's
       * {@link com.example.agentflow.messaging.application.MessageDeliverer} stays un-wired and
       * rows stay {@code PENDING}, never lost.
       */
      private boolean enabled = false;

      /**
       * The broker contact point, bound from {@code agentflow.kafka.producer.bootstrap-servers}
       * (typically {@code ${AGENTFLOW_KAFKA_BOOTSTRAP:...}} from the environment). The value here
       * is only a local development placeholder and must be overridden for any real deployment; it
       * is only read when {@link #enabled} is {@code true}.
       */
      private String bootstrapServers = "localhost:9092";

      /**
       * Number of replicas that must acknowledge a write before it is considered successful (Kafka
       * {@code acks}). {@code all} is the safest: the broker confirms only once every in-sync
       * replica holds the record, so a delivered envelope is durable before the publisher marks it
       * {@code SENT}.
       */
      private String acks = "all";

      /**
       * Maximum time, in seconds, the producer waits for a send to complete before the outcome is
       * treated as <b>ambiguous</b> (Kafka {@code delivery.timeout.ms}, converted to milliseconds).
       * An ambiguous outcome is never marked delivered: the row is re-published at a
       * bounded-backoff instant (S06-08 / S06-11). The value must be at least {@code linger.ms +
       * request.timeout.ms} (Kafka rejects a smaller {@code delivery.timeout.ms}); 60 s leaves
       * comfortable margin over the Kafka defaults (linger 5 ms + request.timeout 30 s).
       */
      @Min(
          value = 1,
          message = "agentflow.kafka.producer.delivery-timeout-seconds must be at least 1")
      private long deliveryTimeoutSeconds = 60;

      /**
       * Number of times a request-failed fault is retried internally by the producer (Kafka {@code
       * retries}). Combined with the publisher's own bounded retry (S06-07); not a promise of
       * exactly-once delivery.
       */
      @Min(value = 0, message = "agentflow.kafka.producer.retries must be non-negative")
      private int retries = 3;

      /** Pause, in milliseconds, between internal retries (Kafka {@code retry.backoff.ms}). */
      @Min(value = 0, message = "agentflow.kafka.producer.retry-backoff-ms must be non-negative")
      private long retryBackoffMs = 100;

      /**
       * Size, in bytes, of the batch of records sent in one request (Kafka {@code batch.size}).
       * Small batches keep poll latency low; larger batches improve throughput.
       */
      @Min(value = 1, message = "agentflow.kafka.producer.batch-size must be at least 1")
      private int batchSize = 16_384;

      /**
       * Time, in milliseconds, the producer waits before sending a batch (Kafka {@code linger.ms}).
       * A small value keeps envelopes on the move; a larger value batches more per request.
       */
      @Min(value = 0, message = "agentflow.kafka.producer.linger-ms must be non-negative")
      private long lingerMs = 5;

      /**
       * Total bytes the producer buffers for in-flight requests (Kafka {@code buffer.memory}).
       * Bounded so a slow broker cannot grow the buffer without limit.
       */
      @Min(value = 1, message = "agentflow.kafka.producer.buffer-memory must be at least 1")
      private int bufferMemory = 33_554_432;

      /**
       * Compression codec for produced records (Kafka {@code compression.type}). {@code none} by
       * default; enable {@code gzip}/{@code snappy}/{@code lz4} to reduce network volume.
       */
      private String compressionType = "none";

      public boolean isEnabled() {
        return enabled;
      }

      public void setEnabled(boolean enabled) {
        this.enabled = enabled;
      }

      public String getBootstrapServers() {
        return bootstrapServers;
      }

      public void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
      }

      public String getAcks() {
        return acks;
      }

      public void setAcks(String acks) {
        this.acks = acks;
      }

      public long getDeliveryTimeoutSeconds() {
        return deliveryTimeoutSeconds;
      }

      public void setDeliveryTimeoutSeconds(long deliveryTimeoutSeconds) {
        this.deliveryTimeoutSeconds = deliveryTimeoutSeconds;
      }

      public int getRetries() {
        return retries;
      }

      public void setRetries(int retries) {
        this.retries = retries;
      }

      public long getRetryBackoffMs() {
        return retryBackoffMs;
      }

      public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
      }

      public int getBatchSize() {
        return batchSize;
      }

      public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
      }

      public long getLingerMs() {
        return lingerMs;
      }

      public void setLingerMs(long lingerMs) {
        this.lingerMs = lingerMs;
      }

      public int getBufferMemory() {
        return bufferMemory;
      }

      public void setBufferMemory(int bufferMemory) {
        this.bufferMemory = bufferMemory;
      }

      public String getCompressionType() {
        return compressionType;
      }

      public void setCompressionType(String compressionType) {
        this.compressionType = compressionType;
      }
    }
  }
}
