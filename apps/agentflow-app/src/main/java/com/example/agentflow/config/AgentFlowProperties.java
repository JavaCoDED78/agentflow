package com.example.agentflow.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
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
  @Valid private final Checkpoint checkpoint = new Checkpoint();
  @Valid private final Security security = new Security();
  @Valid private final Outbox outbox = new Outbox();
  @Valid private final Inbox inbox = new Inbox();
  @Valid private final Kafka kafka = new Kafka();
  @Valid private final Worker worker = new Worker();
  @Valid private final Tools tools = new Tools();
  @Valid private final Ai ai = new Ai();
  @Valid private final Upload upload = new Upload();
  private final Storage storage = new Storage();
  private final Ingestion ingestion = new Ingestion();
  private final Chunking chunking = new Chunking();

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
   * @return the validated workflow-graph settings
   */
  public Graph getGraph() {
    return graph;
  }

  /**
   * @return the validated checkpoint settings
   */
  public Checkpoint getCheckpoint() {
    return checkpoint;
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
   * Step-checkpoint storage settings (S07-07). Bound from {@code agentflow.checkpoint.*}. The
   * checkpoint is the persisted per-step result that lets recovery skip a completed pure step
   * instead of re-executing it (S07-07 acceptance).
   */
  public static class Checkpoint {

    /**
     * Maximum size, in characters, of a step output stored inline in the {@code
     * workflow_step_runs.output_inline} column. Outputs up to this bound are persisted in the row
     * (the small, hot path for pure steps such as a calculator result); a larger output is written
     * to object storage and only its reference is kept in {@code workflow_step_runs.output_ref}
     * (the large-payload path, S15-06). Default 256 KiB; a value below 1 fails startup.
     */
    @Min(value = 1, message = "agentflow.checkpoint.max-inline-bytes must be at least 1")
    private int maxInlineBytes = 262_144;

    /**
     * @return the maximum inline checkpoint output size in characters (default {@code 262144}, 256
     *     KiB)
     */
    public int getMaxInlineBytes() {
      return maxInlineBytes;
    }

    /**
     * @param maxInlineBytes the maximum inline checkpoint output size in characters (at least 1)
     */
    public void setMaxInlineBytes(int maxInlineBytes) {
      this.maxInlineBytes = maxInlineBytes;
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
   * @return the validated workflow-worker (lease / heartbeat / recovery) settings
   */
  public Worker getWorker() {
    return worker;
  }

  /**
   * @return the validated tool-executor (timeout/concurrency bounds) settings (S08-10)
   */
  public Tools getTools() {
    return tools;
  }

  /**
   * @return the validated AI provider resilience settings (S09-09: connect/read/overall timeout,
   *     bounded retry with jitter, parallel-call cap)
   */
  public Ai getAi() {
    return ai;
  }

  /**
   * @return the validated object-storage (Garage) settings (S10-02)
   */
  public Storage getStorage() {
    return storage;
  }

  /**
   * @return the validated document-upload limits (S10-03)
   */
  public Upload getUpload() {
    return upload;
  }

  /**
   * @return the validated document-extraction limits (S10-07)
   */
  public Ingestion getIngestion() {
    return ingestion;
  }

  /**
   * The deterministic-chunking parameters (S10-09). Bound from {@code
   * agentflow.ingestion.chunking.*}.
   *
   * @return the chunking configuration group
   */
  public Chunking getChunking() {
    return chunking;
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

  /**
   * Workflow-worker settings (S07-03…S07-09). Bound from {@code agentflow.worker.*}. The lease
   * tuning lives here, not in code (apps/agentflow-app AGENTS.md): the atomic claim uses {@link
   * Lease#getDurationSeconds()} to compute {@code lease_until}, and {@link #getInstanceId()}
   * identifies this worker instance on {@code lease_owner}.
   */
  public static class Worker {

    private final Lease lease = new Lease();
    @Valid private final Retry retry = new Retry();

    /**
     * This worker instance id, bound from {@code agentflow.worker.instance-id}, recorded on {@code
     * workflow_runs.lease_owner} so exactly one live worker is recognised as the run owner.
     * Nullable on purpose: when unset a UUID is generated at construction and logged, so the
     * identity is always present for the recovery scan even in a profile that does not name one.
     */
    private String instanceId = null;

    /**
     * Whether the stale-run recovery scheduler (S07-09) runs in this process. {@code false} by
     * default so the skeleton (and the API role) does not reclaim runs; the {@code workflow-worker}
     * role enables it, so the lease recovery lives only where a live worker can drive a re-claimed
     * run to progress. Enabled explicitly, not "everywhere by default" (apps/agentflow-app
     * AGENTS.md). Bound from {@code agentflow.worker.recovery-enabled}.
     */
    private boolean recoveryEnabled = false;

    /**
     * Maximum number of expired-lease runs the recovery scheduler re-claims in one pass (S07-09).
     * Bounded so one recovery scan takes ownership of a limited batch rather than table-scanning
     * and re-claiming every stale run at once. The worker processes runs serially, so this also
     * bounds how many runs a single recovery pass takes ownership of. Default 8; a value below 1
     * fails startup. Bound from {@code agentflow.worker.max-concurrent-runs}.
     */
    @Min(value = 1, message = "agentflow.worker.max-concurrent-runs must be at least 1")
    private int maxConcurrentRuns = 8;

    /**
     * Recovery-scheduler poll cadence, in milliseconds (S07-09). Drives the {@code @Scheduled} scan
     * so it runs at {@code recovery-interval-seconds} (the lease default is 5 s, hence 5000 ms). At
     * least 1 s; a value below 1 fails startup. Bound from {@code
     * agentflow.worker.recovery-fixed-rate-ms}.
     */
    @Min(value = 1, message = "agentflow.worker.recovery-fixed-rate-ms must be at least 1")
    private long recoveryFixedRateMs = 5_000L;

    /**
     * Whether the worker poll loop (S07-13, the graceful-shutdown lifecycle) drives the {@link
     * WorkflowWorker} in this process. {@code true} by default for the {@code workflow-worker}
     * role; the {@code api} role and the skeleton leave it {@code false} so no loop runs without a
     * live worker. Enabled explicitly, not "everywhere by default" (apps/agentflow-app AGENTS.md).
     * Bound from {@code agentflow.worker.loop-enabled}.
     */
    private boolean loopEnabled = true;

    /**
     * Whether the ingestion indexing worker (S10-13 — the Kafka consumer of {@code
     * agentflow.document.ingestion-requested.v1} driving extract → chunk → embed → index) runs in
     * this process. {@code false} by default: the {@code api} role and the DB-less skeleton never
     * start a Kafka consumer; the {@code ingestion-worker} role enables it explicitly, not
     * "everywhere by default" (apps/agentflow-app AGENTS.md). Bound from {@code
     * agentflow.worker.ingestion-loop-enabled}.
     */
    private boolean ingestionLoopEnabled = false;

    /**
     * The Kafka consumer long-poll timeout of the ingestion indexing worker, in milliseconds
     * (S10-13): how long one {@code poll} waits for records before the loop re-checks the shutdown
     * flag. Bounds the graceful-stop latency (a wakeup interrupts the wait anyway). At least 100
     * ms; a value below 100 fails startup. Bound from {@code
     * agentflow.worker.ingestion-poll-timeout-ms}.
     */
    @Min(value = 100, message = "agentflow.worker.ingestion-poll-timeout-ms must be at least 100")
    private long ingestionPollTimeoutMs = 1_000L;

    /**
     * The bounded immediate retry of the ingestion indexing business change (S10-13, the {@code
     * InboxDedupService} attempt budget): how many times a transiently failing indexing pass is
     * re-run inside the inbox transaction before the event is held (RETRY / DLQ, S06-11). At least
     * 1; a value below 1 fails startup. Bound from {@code agentflow.worker.ingestion-max-attempts}.
     */
    @Min(value = 1, message = "agentflow.worker.ingestion-max-attempts must be at least 1")
    private int ingestionMaxAttempts = 3;

    /**
     * The tenants the worker poll loop serves. The loop calls {@code WorkflowWorker#pollOnce} for
     * each of these on every cadence; an empty list serves nothing until a tenant source is wired.
     * This is the local-skeleton placeholder for the eventual per-tenant Kafka-consumer driver: the
     * production driver discovers tenants from the trusted identity layer, never from a body/query
     * header (invariant 1), so this list is an operator-set start-up set, never a trust anchor.
     * Defaults to empty. Bound from {@code agentflow.worker.tenants}.
     */
    private List<UUID> tenants = List.of();

    /**
     * Maximum time in seconds the worker lifecycle waits for the in-flight step to finish before it
     * abandons the drain on shutdown (S07-13). The bounded drain gives a step a fair chance to
     * complete cleanly; when the timeout elapses the loop thread is left to run (its run-level
     * lease then expires and the stale-run recovery scheduler re-claims the run, so termination
     * never leaves a {@code RUNNING} run without recovery). At least 1 s; a value below 1 fails
     * startup. Bound from {@code agentflow.worker.shutdown.timeout-seconds}.
     */
    @Min(value = 1, message = "agentflow.worker.shutdown.timeout-seconds must be at least 1")
    private long shutdownTimeoutSeconds = 30L;

    /**
     * @return whether the worker poll loop runs in this process (see {@link #loopEnabled})
     */
    public boolean isLoopEnabled() {
      return loopEnabled;
    }

    /**
     * @param loopEnabled whether the worker poll loop runs in this process (default {@code true})
     */
    public void setLoopEnabled(boolean loopEnabled) {
      this.loopEnabled = loopEnabled;
    }

    /**
     * @return whether the ingestion indexing worker runs in this process (default {@code false})
     */
    public boolean isIngestionLoopEnabled() {
      return ingestionLoopEnabled;
    }

    /**
     * @param ingestionLoopEnabled whether the ingestion indexing worker runs in this process
     *     (default {@code false}; the {@code ingestion-worker} role enables it)
     */
    public void setIngestionLoopEnabled(boolean ingestionLoopEnabled) {
      this.ingestionLoopEnabled = ingestionLoopEnabled;
    }

    /**
     * @return the Kafka consumer long-poll timeout of the ingestion indexing worker, in
     *     milliseconds (default {@code 1000})
     */
    public long getIngestionPollTimeoutMs() {
      return ingestionPollTimeoutMs;
    }

    /**
     * @param ingestionPollTimeoutMs the Kafka consumer long-poll timeout in milliseconds (at least
     *     100)
     */
    public void setIngestionPollTimeoutMs(long ingestionPollTimeoutMs) {
      this.ingestionPollTimeoutMs = ingestionPollTimeoutMs;
    }

    /**
     * @return the bounded immediate retry of the ingestion indexing business change (default {@code
     *     3})
     */
    public int getIngestionMaxAttempts() {
      return ingestionMaxAttempts;
    }

    /**
     * @param ingestionMaxAttempts the bounded immediate retry of the ingestion indexing business
     *     change (at least 1)
     */
    public void setIngestionMaxAttempts(int ingestionMaxAttempts) {
      this.ingestionMaxAttempts = ingestionMaxAttempts;
    }

    /**
     * @return the tenants the worker poll loop serves (empty by default)
     */
    public List<UUID> getTenants() {
      return tenants;
    }

    /**
     * @param tenants the tenants the worker poll loop serves (an empty list serves nothing)
     */
    public void setTenants(List<UUID> tenants) {
      this.tenants = tenants == null ? List.of() : tenants;
    }

    /**
     * @return the bounded-drain ceiling on shutdown, in seconds (default {@code 30})
     */
    public long getShutdownTimeoutSeconds() {
      return shutdownTimeoutSeconds;
    }

    /**
     * @param shutdownTimeoutSeconds the bounded-drain ceiling on shutdown in seconds (at least 1)
     */
    public void setShutdownTimeoutSeconds(long shutdownTimeoutSeconds) {
      this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    /**
     * The worker poll loop cadence, in milliseconds (S07-13). The {@link WorkerLifecycle} sleeps
     * this long between {@code WorkflowWorker#pollOnce} passes over the configured tenants. At
     * least 100 ms; a value below 100 fails startup (a sub-second poll loop is not a loop, it is a
     * spin). Bound from {@code agentflow.worker.poll-interval-ms}.
     */
    @Min(value = 100, message = "agentflow.worker.poll-interval-ms must be at least 100")
    private long pollIntervalMs = 5_000L;

    /**
     * @return the worker poll loop cadence in milliseconds (default {@code 5000})
     */
    public long getPollIntervalMs() {
      return pollIntervalMs;
    }

    /**
     * @param pollIntervalMs the worker poll loop cadence in milliseconds (at least 100)
     */
    public void setPollIntervalMs(long pollIntervalMs) {
      this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * @return the lease tuning group
     */
    public Lease getLease() {
      return lease;
    }

    /**
     * @return the retry-tuning group (S07-10)
     */
    public Retry getRetry() {
      return retry;
    }

    /**
     * @return this worker instance id, or {@code null} when the profile left it unset (a UUID is
     *     generated by {@link RunLeaseService} so {@code lease_owner} is never blank)
     */
    public String getInstanceId() {
      return instanceId;
    }

    /**
     * @param instanceId the worker instance id to record on {@code lease_owner} (nullable)
     */
    public void setInstanceId(String instanceId) {
      this.instanceId = instanceId;
    }

    /**
     * @return whether the stale-run recovery scheduler (S07-09) runs in this process (default
     *     {@code false}, enabled by the {@code workflow-worker} role)
     */
    public boolean isRecoveryEnabled() {
      return recoveryEnabled;
    }

    /**
     * @param recoveryEnabled whether the recovery scheduler runs in this process (default {@code
     *     false})
     */
    public void setRecoveryEnabled(boolean recoveryEnabled) {
      this.recoveryEnabled = recoveryEnabled;
    }

    /**
     * @return the maximum number of expired-lease runs the recovery scheduler re-claims per pass
     *     (default {@code 8})
     */
    public int getMaxConcurrentRuns() {
      return maxConcurrentRuns;
    }

    /**
     * @param maxConcurrentRuns the per-pass re-claim bound (at least 1)
     */
    public void setMaxConcurrentRuns(int maxConcurrentRuns) {
      this.maxConcurrentRuns = maxConcurrentRuns;
    }

    /**
     * @return the recovery-scheduler poll cadence in milliseconds (default {@code 5000})
     */
    public long getRecoveryFixedRateMs() {
      return recoveryFixedRateMs;
    }

    /**
     * @param recoveryFixedRateMs the recovery-scheduler poll cadence in milliseconds (at least 1)
     */
    public void setRecoveryFixedRateMs(long recoveryFixedRateMs) {
      this.recoveryFixedRateMs = recoveryFixedRateMs;
    }

    /**
     * Lease lifetime and cadence (ADR-011). The three intervals keep the relationships that make
     * reclamation correct and fast: a live worker heartbeats (S07-04) at least three times within
     * one {@code duration-seconds} TTL, and the recovery scheduler (S07-09) scans at least every
     * {@code recovery-interval-seconds}, so a dead owner is noticed within {@code duration +
     * recovery-interval}. These defaults match the test config that bounds the S07-15 resume time.
     */
    public static class Lease {

      /**
       * Lease lifetime, in seconds, without a heartbeat. Computed as {@code lease_until = now +
       * duration-seconds} on claim. Default 30: well above a normal step, below the crash-detection
       * horizon.
       */
      @Min(value = 1, message = "agentflow.worker.lease.duration-seconds must be at least 1")
      private int durationSeconds = 30;

      /**
       * Heartbeat cadence, in seconds. A live worker extends the lease this often, so a single lost
       * heartbeat (or GC pause) never expires a healthy lease; rule {@code interval <= duration /
       * 3}. Default 10.
       */
      @Min(
          value = 1,
          message = "agentflow.worker.lease.heartbeat-interval-seconds must be at least 1")
      private int heartbeatIntervalSeconds = 10;

      /**
       * How often the recovery scheduler (S07-09) scans for expired leases, in seconds. Bounded so
       * {@code duration + recovery-interval <= 60} in the test config (the S07-15 criterion).
       * Default 5.
       */
      @Min(
          value = 1,
          message = "agentflow.worker.lease.recovery-interval-seconds must be at least 1")
      private int recoveryIntervalSeconds = 5;

      /**
       * @return the lease lifetime in seconds (default {@code 30})
       */
      public int getDurationSeconds() {
        return durationSeconds;
      }

      /**
       * @param durationSeconds the lease lifetime in seconds (at least 1)
       */
      public void setDurationSeconds(int durationSeconds) {
        this.durationSeconds = durationSeconds;
      }

      /**
       * @return the heartbeat cadence in seconds (default {@code 10})
       */
      public int getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
      }

      /**
       * @param heartbeatIntervalSeconds the heartbeat cadence in seconds (at least 1)
       */
      public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
      }

      /**
       * @return the recovery scan interval in seconds (default {@code 5})
       */
      public int getRecoveryIntervalSeconds() {
        return recoveryIntervalSeconds;
      }

      /**
       * @param recoveryIntervalSeconds the recovery scan interval in seconds (at least 1)
       */
      public void setRecoveryIntervalSeconds(int recoveryIntervalSeconds) {
        this.recoveryIntervalSeconds = recoveryIntervalSeconds;
      }
    }

    /**
     * Retry policy for a failed step (S07-10). Bound from {@code agentflow.worker.retry.*}. The
     * values back a {@link com.example.agentflow.workflowexecution.domain.RetryPolicy} value
     * object; they are configuration, not magic numbers (apps/agentflow-app AGENTS.md: retry lives
     * in configuration). The {@code multiplier} and the {@code maxDelay >= baseDelay} relationship
     * are not Jakarta-validated here — {@link
     * com.example.agentflow.workflowexecution.domain.RetryPolicy#of} rejects an invalid combination
     * at bean creation, so a misconfiguration still fails boot.
     */
    public static class Retry {

      /**
       * Maximum number of attempts (the initial attempt plus retries) before a step fails
       * permanently. Default 3.
       */
      @Min(value = 1, message = "agentflow.worker.retry.max-attempts must be at least 1")
      private int maxAttempts = 3;

      /**
       * Base delay, in seconds, before the first retry ({@code multiplier^0}). Grows exponentially
       * from it. Default 1; 0 is allowed (no delay on the first retry).
       */
      @Min(value = 0, message = "agentflow.worker.retry.base-delay-seconds must be non-negative")
      private long baseDelaySeconds = 1;

      /**
       * Exponential growth factor applied per attempt. {@code 1.0} keeps the delay constant; a
       * value above 1 grows it. Must be at least 1.0 (validated in {@link
       * com.example.agentflow.workflowexecution.domain.RetryPolicy#of}). Default 2.0.
       */
      private double multiplier = 2.0;

      /**
       * Maximum delay, in seconds, every backoff is capped to. Must be at least {@code
       * baseDelay-seconds} (validated in {@link
       * com.example.agentflow.workflowexecution.domain.RetryPolicy#of}). Default 60.
       */
      @Min(value = 1, message = "agentflow.worker.retry.max-delay-seconds must be at least 1")
      private long maxDelaySeconds = 60;

      /**
       * Whether full jitter is applied to the computed backoff (reduces a thundering herd while
       * keeping the maximum wait bounded by {@code max-delay-seconds}). Default true.
       */
      private boolean jitter = true;

      /**
       * @return the maximum number of attempts before a step fails permanently (default {@code 3})
       */
      public int getMaxAttempts() {
        return maxAttempts;
      }

      /**
       * @param maxAttempts the maximum number of attempts before a step fails permanently (at least
       *     1)
       */
      public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
      }

      /**
       * @return the base retry delay in seconds (default {@code 1})
       */
      public long getBaseDelaySeconds() {
        return baseDelaySeconds;
      }

      /**
       * @param baseDelaySeconds the base retry delay in seconds (non-negative)
       */
      public void setBaseDelaySeconds(long baseDelaySeconds) {
        this.baseDelaySeconds = baseDelaySeconds;
      }

      /**
       * @return the exponential growth factor (at least 1.0)
       */
      public double getMultiplier() {
        return multiplier;
      }

      /**
       * @param multiplier the exponential growth factor (at least 1.0)
       */
      public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
      }

      /**
       * @return the capped maximum retry delay in seconds (default {@code 60})
       */
      public long getMaxDelaySeconds() {
        return maxDelaySeconds;
      }

      /**
       * @param maxDelaySeconds the capped maximum retry delay in seconds (at least 1)
       */
      public void setMaxDelaySeconds(long maxDelaySeconds) {
        this.maxDelaySeconds = maxDelaySeconds;
      }

      /**
       * @return whether full jitter is applied to the backoff (default {@code true})
       */
      public boolean isJitter() {
        return jitter;
      }

      /**
       * @param jitter whether full jitter is applied to the backoff
       */
      public void setJitter(boolean jitter) {
        this.jitter = jitter;
      }
    }
  }

  /**
   * Tool-executor bounds (S08-10). Bound from {@code agentflow.tools.executor.*}. The limits live
   * here, not in code (apps/agentflow-app AGENTS.md: budgets and limits are configuration). They
   * back the {@link com.example.agentflow.toolexecution.application.BoundedToolExecutor} bean: the
   * per-tool timeout itself is not configured here — it is part of every {@code ToolDescriptor}
   * (S08-01) — these are the pool and the concurrency caps around the calls.
   */
  public static class Tools {

    @Valid private final Executor executor = new Executor();

    /**
     * @return the validated tool-executor pool/concurrency settings
     */
    public Executor getExecutor() {
      return executor;
    }

    /** Pool and concurrency caps for the bounded tool executor (S08-10). */
    public static class Executor {

      /**
       * Size of the fixed thread pool the tool calls run on. Bounded so a burst of slow calls
       * cannot spawn unbounded threads; the pool threads are shared by every tenant. Default 8. At
       * least 1; a value below 1 fails startup.
       */
      @Min(value = 1, message = "agentflow.tools.executor.pool-size must be at least 1")
      private int poolSize = 8;

      /**
       * Maximum number of tool calls the whole process runs at once (the global cap). Calls beyond
       * it are rejected immediately with a {@code concurrency_limited} outcome, never queued.
       * Default 32. At least 1; a value below 1 fails startup.
       */
      @Min(
          value = 1,
          message = "agentflow.tools.executor.global-max-concurrency must be at least 1")
      private int globalMaxConcurrency = 32;

      /**
       * Maximum number of tool calls a single tenant may occupy at once (the per-tenant cap), so
       * one noisy tenant cannot starve the rest. Default 8. At least 1; a value below 1 fails
       * startup.
       */
      @Min(
          value = 1,
          message = "agentflow.tools.executor.per-tenant-max-concurrency must be at least 1")
      private int perTenantMaxConcurrency = 8;

      /**
       * @return the tool-call pool size (default {@code 8})
       */
      public int getPoolSize() {
        return poolSize;
      }

      /**
       * @param poolSize the tool-call pool size (at least 1)
       */
      public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
      }

      /**
       * @return the global concurrency cap (default {@code 32})
       */
      public int getGlobalMaxConcurrency() {
        return globalMaxConcurrency;
      }

      /**
       * @param globalMaxConcurrency the global concurrency cap (at least 1)
       */
      public void setGlobalMaxConcurrency(int globalMaxConcurrency) {
        this.globalMaxConcurrency = globalMaxConcurrency;
      }

      /**
       * @return the per-tenant concurrency cap (default {@code 8})
       */
      public int getPerTenantMaxConcurrency() {
        return perTenantMaxConcurrency;
      }

      /**
       * @param perTenantMaxConcurrency the per-tenant concurrency cap (at least 1)
       */
      public void setPerTenantMaxConcurrency(int perTenantMaxConcurrency) {
        this.perTenantMaxConcurrency = perTenantMaxConcurrency;
      }
    }
  }

  /**
   * AI provider resilience settings (S09-09). Bound from {@code agentflow.ai.*}; these keep the
   * provider timeouts, bounded retry-backoff and the parallel-call cap in configuration rather than
   * hard-coded in the adapter. The connect/read timeouts are applied to the underlying HTTP client;
   * the overall timeout, the bounded retry with jitter and the concurrency cap are enforced by the
   * resilient wrapper ({@code ai.provider.ResilientChatModel}).
   */
  public static class Ai {

    @Valid private final Timeout timeout = new Timeout();
    @Valid private final Retry retry = new Retry();

    /** Maximum number of concurrent chat calls to the provider (default 4, at least 1). */
    @Min(value = 1, message = "agentflow.ai.max-parallel-calls must be at least 1")
    private int maxParallelCalls = 4;

    /**
     * @return the maximum number of concurrent chat calls (default {@code 4})
     */
    public int getMaxParallelCalls() {
      return maxParallelCalls;
    }

    /**
     * @param maxParallelCalls the maximum number of concurrent chat calls (at least 1)
     */
    public void setMaxParallelCalls(int maxParallelCalls) {
      this.maxParallelCalls = maxParallelCalls;
    }

    /**
     * @return the validated timeout settings (connect/read/overall)
     */
    public Timeout getTimeout() {
      return timeout;
    }

    /**
     * @return the validated retry settings (bounded backoff with jitter)
     */
    public Retry getRetry() {
      return retry;
    }

    /** Connect/read/overall timeouts (seconds). */
    public static class Timeout {

      /** Connect timeout, in seconds, before a connection is established (default 10). */
      @Min(value = 1, message = "agentflow.ai.timeout.connect-seconds must be at least 1")
      private long connectSeconds = 10;

      /** Read timeout, in seconds, for the server to produce a response (default 120). */
      @Min(value = 1, message = "agentflow.ai.timeout.read-seconds must be at least 1")
      private long readSeconds = 120;

      /**
       * Overall timeout, in seconds, for the whole chat call measured from the wall clock; the
       * resilient wrapper aborts the call and fails it once it is exceeded (default 150).
       */
      @Min(value = 1, message = "agentflow.ai.timeout.overall-seconds must be at least 1")
      private long overallSeconds = 150;

      /**
       * @return the connect timeout in seconds (default {@code 10})
       */
      public long getConnectSeconds() {
        return connectSeconds;
      }

      /**
       * @param connectSeconds the connect timeout in seconds (at least 1)
       */
      public void setConnectSeconds(long connectSeconds) {
        this.connectSeconds = connectSeconds;
      }

      /**
       * @return the read timeout in seconds (default {@code 120})
       */
      public long getReadSeconds() {
        return readSeconds;
      }

      /**
       * @param readSeconds the read timeout in seconds (at least 1)
       */
      public void setReadSeconds(long readSeconds) {
        this.readSeconds = readSeconds;
      }

      /**
       * @return the overall timeout in seconds (default {@code 150})
       */
      public long getOverallSeconds() {
        return overallSeconds;
      }

      /**
       * @param overallSeconds the overall timeout in seconds (at least 1)
       */
      public void setOverallSeconds(long overallSeconds) {
        this.overallSeconds = overallSeconds;
      }
    }

    /**
     * Bounded retry with full jitter (S09-09). Mirrors the {@code workflowexecution}/{@code
     * toolexecution} retry policy shape (same parameters, different module) but is the {@code ai}
     * module's own copy: the architecture gate forbids importing the other modules' {@code
     * domain.RetryPolicy}, so each module owns its retry descriptor.
     */
    public static class Retry {

      /**
       * Maximum number of attempts (the initial attempt plus retries) before the call fails
       * permanently. Default 3.
       */
      @Min(value = 1, message = "agentflow.ai.retry.max-attempts must be at least 1")
      private int maxAttempts = 3;

      /**
       * Base delay, in seconds, before the first retry ({@code multiplier^0}). Grows exponentially
       * from it. Default 1; 0 is allowed (no delay on the first retry).
       */
      @Min(value = 0, message = "agentflow.ai.retry.base-delay-seconds must be non-negative")
      private long baseDelaySeconds = 1;

      /**
       * Exponential growth factor applied per attempt. {@code 1.0} keeps the delay constant; a
       * value above 1 grows it. Must be at least 1.0 (validated in the {@code
       * ai.application.ProviderResiliencePolicy} value object). Default 2.0.
       */
      private double multiplier = 2.0;

      /**
       * Maximum delay, in seconds, every backoff is capped to. Must be at least {@code
       * baseDelay-seconds} (validated in {@code ProviderResiliencePolicy}). Default 60.
       */
      @Min(value = 1, message = "agentflow.ai.retry.max-delay-seconds must be at least 1")
      private long maxDelaySeconds = 60;

      /**
       * Whether full jitter is applied to the computed backoff (reduces a thundering herd while
       * keeping the maximum wait bounded by {@code max-delay-seconds}). Default true.
       */
      private boolean jitter = true;

      /**
       * @return the maximum number of attempts before the call fails permanently (default {@code
       *     3})
       */
      public int getMaxAttempts() {
        return maxAttempts;
      }

      /**
       * @param maxAttempts the maximum number of attempts before the call fails permanently (at
       *     least 1)
       */
      public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
      }

      /**
       * @return the base retry delay in seconds (default {@code 1})
       */
      public long getBaseDelaySeconds() {
        return baseDelaySeconds;
      }

      /**
       * @param baseDelaySeconds the base retry delay in seconds (non-negative)
       */
      public void setBaseDelaySeconds(long baseDelaySeconds) {
        this.baseDelaySeconds = baseDelaySeconds;
      }

      /**
       * @return the exponential growth factor (at least 1.0)
       */
      public double getMultiplier() {
        return multiplier;
      }

      /**
       * @param multiplier the exponential growth factor (at least 1.0)
       */
      public void setMultiplier(double multiplier) {
        this.multiplier = multiplier;
      }

      /**
       * @return the capped maximum retry delay in seconds (default {@code 60})
       */
      public long getMaxDelaySeconds() {
        return maxDelaySeconds;
      }

      /**
       * @param maxDelaySeconds the capped maximum retry delay in seconds (at least 1)
       */
      public void setMaxDelaySeconds(long maxDelaySeconds) {
        this.maxDelaySeconds = maxDelaySeconds;
      }

      /**
       * @return whether full jitter is applied (default {@code true})
       */
      public boolean isJitter() {
        return jitter;
      }

      /**
       * @param jitter whether full jitter is applied
       */
      public void setJitter(boolean jitter) {
        this.jitter = jitter;
      }
    }
  }

  /**
   * Object-storage (Garage) connection and access settings (S10-02). Bound from {@code
   * agentflow.storage.*}. This group describes <b>which</b> bucket the document payloads live in,
   * <b>where</b> the S3-compatible API is, and the <b>access policy</b>; the S3 client that
   * consumes it (built in {@code S10-03}) reads and writes payloads through the app only, never
   * through a public or anonymous URL (see the {@code S10-02} acceptance). The credentials
   * themselves come from the environment ({@code AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY} / {@code
   * AGENTFLOW_OBJECT_STORAGE_SECRET_KEY}), never from configuration or source (invariant 8).
   *
   * <p>The bucket is <b>private</b>: {@link #getPublicAccess()} defaults to {@link
   * PublicAccess#DISABLED}, so no object is ever addressable through a public URL without
   * authorization. Objects are read and written only through the app, which owns the credentials
   * and re-checks the tenant (invariant 1) on every access; the object-key convention that
   * namespaces every key by {@code tenantId} ({@link
   * com.example.agentflow.knowledgebase.domain.DocumentObjectKey}) is a defence-in-depth on top of
   * that app-layer decision.
   */
  public static class Storage {

    /**
     * The S3 endpoint (Garage, path-style SigV4). No default in the base profile: the endpoint is
     * environment-specific and is supplied by the local / kubernetes profiles (and the local {@code
     * .env}, via {@code AGENTFLOW_OBJECT_STORAGE_ENDPOINT}). The S3 client (S10-03) requires it; a
     * {@code null} endpoint fails clearly when the client is built, never silently.
     */
    private String endpoint = null;

    /** The bucket object payloads live in. Default {@code agentflow} (S01-15 / ADR-013). */
    private String bucket = "agentflow";

    /**
     * The S3 region. Garage serves on region {@code garage}; kept configurable for the S3 contract.
     */
    private String region = "garage";

    /**
     * Whether anonymous / public access to objects is permitted. {@link PublicAccess#DISABLED} by
     * default: objects are addressed only through the app and never through a public or anonymous
     * URL (the {@code S10-02} acceptance). {@link PublicAccess#PUBLIC} exists only so an operator
     * can explicitly opt in; the platform never enables it by default (invariant 8).
     */
    private PublicAccess publicAccess = PublicAccess.DISABLED;

    /**
     * @return the S3 endpoint (nullable; supplied by a profile, required by the S3 client)
     */
    public String getEndpoint() {
      return endpoint;
    }

    /**
     * @param endpoint the S3 endpoint
     */
    public void setEndpoint(String endpoint) {
      this.endpoint = endpoint;
    }

    /**
     * @return the bucket name (default {@code agentflow})
     */
    public String getBucket() {
      return bucket;
    }

    /**
     * @param bucket the bucket name
     */
    public void setBucket(String bucket) {
      this.bucket = bucket;
    }

    /**
     * @return the S3 region (default {@code garage})
     */
    public String getRegion() {
      return region;
    }

    /**
     * @param region the S3 region
     */
    public void setRegion(String region) {
      this.region = region;
    }

    /**
     * @return whether anonymous / public access is permitted (default {@link
     *     PublicAccess#DISABLED})
     */
    public PublicAccess getPublicAccess() {
      return publicAccess;
    }

    /**
     * @param publicAccess the access policy
     */
    public void setPublicAccess(PublicAccess publicAccess) {
      this.publicAccess = publicAccess;
    }

    /**
     * Object-access policy. {@link #DISABLED} (the default) means the bucket is private and objects
     * are read only through the app; {@link #PUBLIC} opts an operator in to anonymous / public
     * addressing, which the platform never enables by default (invariant 8 / the {@code S10-02}
     * acceptance).
     */
    public enum PublicAccess {
      DISABLED,
      PUBLIC
    }
  }

  /**
   * Document-upload limits (S10-03). Bound from {@code agentflow.upload.*}. These are the
   * accept/bound checks that run <b>before</b> a single byte is written to object storage and
   * before a single metadata row is committed (the {@code S10-03} acceptance: an oversized or
   * unsupported file is rejected without leaving a dangling metadata row or object). They live in
   * configuration, not in code, following the rule that budgets and limits are configured, and they
   * are validated at startup so a nonsensical limit fails boot rather than silently accepting
   * everything.
   *
   * <p>Three independent bounds:
   *
   * <ul>
   *   <li><b>max-bytes</b> &mdash; the maximum upload payload size; a larger payload is rejected.
   *   <li><b>max-filename-length</b> &mdash; the maximum length of the sanitised client filename
   *       that is stored on the {@code documents} row; the filename is server-sanitised (no path,
   *       no traversal) so it can never become an object path (invariant 1 / {@code S10-04}).
   *   <li><b>max-media-duration-seconds</b> &mdash; the maximum decoded duration, in seconds, of a
   *       media upload (currently {@code audio/wav}); longer media is rejected. Non-media types are
   *       never duration-checked.
   * </ul>
   */
  public static class Upload {

    /** Maximum upload payload size in bytes. Default 10 MiB; at least 1, a value below 1 fails. */
    @Min(value = 1, message = "agentflow.upload.max-bytes must be at least 1")
    private int maxBytes = 10 * 1024 * 1024;

    /** Maximum length of the sanitised filename stored on the document row. Default 255. */
    @Min(value = 1, message = "agentflow.upload.max-filename-length must be at least 1")
    private int maxFileNameLength = 255;

    /**
     * Maximum decoded duration, in seconds, of a media upload before it is rejected. Default 300 (5
     * minutes); at least 1, a value below 1 fails. Only applies to content types flagged as media
     * (currently {@code audio/wav}); static document types are never duration-checked.
     */
    @Min(value = 1, message = "agentflow.upload.max-media-duration-seconds must be at least 1")
    private int maxMediaDurationSeconds = 300;

    /**
     * @return the maximum upload payload size in bytes (default {@code 10485760}, 10 MiB)
     */
    public int getMaxBytes() {
      return maxBytes;
    }

    /**
     * @param maxBytes the maximum upload payload size in bytes (at least 1)
     */
    public void setMaxBytes(int maxBytes) {
      this.maxBytes = maxBytes;
    }

    /**
     * @return the maximum sanitised filename length (default {@code 255})
     */
    public int getMaxFileNameLength() {
      return maxFileNameLength;
    }

    /**
     * @param maxFileNameLength the maximum sanitised filename length (at least 1)
     */
    public void setMaxFileNameLength(int maxFileNameLength) {
      this.maxFileNameLength = maxFileNameLength;
    }

    /**
     * @return the maximum media upload duration in seconds (default {@code 300}, 5 minutes)
     */
    public int getMaxMediaDurationSeconds() {
      return maxMediaDurationSeconds;
    }

    /**
     * @param maxMediaDurationSeconds the maximum media upload duration in seconds (at least 1)
     */
    public void setMaxMediaDurationSeconds(int maxMediaDurationSeconds) {
      this.maxMediaDurationSeconds = maxMediaDurationSeconds;
    }
  }

  /**
   * Document-extraction limits (S10-07). Bound from {@code agentflow.ingestion.*}; these bound the
   * {@code DocumentParser} the ingestion worker runs on an already-uploaded document (extraction
   * happens in the worker, never in the upload use case &mdash; S10-06). The limits live in
   * configuration, not in code: the number of pages read from a PDF and the number of characters
   * retained from the extracted text are capped here so a very large or pathological document
   * cannot exhaust a worker. They are validated at startup so a nonsensical limit fails boot rather
   * than silently accepting everything, mirroring the {@link Upload} limits.
   *
   * <p>Two independent bounds:
   *
   * <ul>
   *   <li><b>max-pages</b> &mdash; the maximum number of pages read from a PDF (default 100). Pages
   *       beyond this cap are not read; {@code ExtractionResult#truncated()} is {@code true} when
   *       the document has more pages than this.
   *   <li><b>max-chars</b> &mdash; the maximum number of characters retained from the extracted
   *       text (default 500000, ~500 KiB). Text beyond this cap is dropped; {@code
   *       ExtractionResult#truncated()} is {@code true} when it was.
   * </ul>
   */
  public static class Ingestion {

    /** Maximum number of pages read from a PDF. Default 100; at least 1, a value below 1 fails. */
    @Min(value = 1, message = "agentflow.ingestion.max-pages must be at least 1")
    private int maxPages = 100;

    /**
     * Maximum number of characters retained from the extracted text. Default 500000 (~500 KiB); at
     * least 1, a value below 1 fails.
     */
    @Min(value = 1, message = "agentflow.ingestion.max-chars must be at least 1")
    private int maxChars = 500 * 1000;

    /**
     * Chunk-embedding batching parameters (S10-12), bound from {@code
     * agentflow.ingestion.embedding.*}.
     */
    @Valid private final Embedding embedding = new Embedding();

    /**
     * Indexing idempotency parameters (S10-13), bound from {@code agentflow.ingestion.indexing.*}.
     */
    @Valid private final Indexing indexing = new Indexing();

    /**
     * @return the maximum number of pages read from a PDF (default {@code 100})
     */
    public int getMaxPages() {
      return maxPages;
    }

    /**
     * @param maxPages the maximum number of pages read from a PDF (at least 1)
     */
    public void setMaxPages(int maxPages) {
      this.maxPages = maxPages;
    }

    /**
     * @return the maximum number of characters retained from the extracted text (default {@code
     *     500000})
     */
    public int getMaxChars() {
      return maxChars;
    }

    /**
     * @param maxChars the maximum characters retained from the extracted text (at least 1)
     */
    public void setMaxChars(int maxChars) {
      this.maxChars = maxChars;
    }

    /**
     * @return the chunk-embedding batching parameters (S10-12)
     */
    public Embedding getEmbedding() {
      return embedding;
    }

    /**
     * @return the indexing idempotency parameters (S10-13)
     */
    public Indexing getIndexing() {
      return indexing;
    }

    /**
     * Chunk-embedding batching parameters (S10-12), bound from {@code
     * agentflow.ingestion.embedding.*}. These back the {@code EmbeddingGenerationService} the
     * ingestion worker runs on a chunk set. As with the extraction limits, the value lives in
     * configuration, not code: the per-call batch size sent to the embedding provider is tuned
     * here, and a nonsensical value fails validation at startup. Provider-side rate limiting and
     * retries are the {@code ai} module's resilience layer ({@code ProviderResiliencePolicy},
     * {@code agentflow.ai.retry.*}), not this group.
     */
    public static class Embedding {

      /** Maximum chunk texts per embedding call. Default 32; at least 1, a value below 1 fails. */
      @Min(value = 1, message = "agentflow.ingestion.embedding.batch-size must be at least 1")
      private int batchSize = 32;

      /**
       * @return the maximum chunk texts per embedding call (default {@code 32})
       */
      public int getBatchSize() {
        return batchSize;
      }

      /**
       * @param batchSize the maximum number of texts per {@link EmbeddingPort} call (at least 1)
       */
      public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
      }
    }

    /**
     * Indexing idempotency parameters (S10-13), bound from {@code agentflow.ingestion.indexing.*}.
     * These back the {@code IngestionService} indexing pass. The redelivery policy decides what a
     * redelivered {@code ingestion-requested} event does with an already-indexed document: {@code
     * skip} (the default) leaves the existing index untouched and the pass is a no-op; {@code
     * update} re-embeds and overwrites the chunk rows in place. The value lives in configuration,
     * not code, and an unknown value fails the wiring at startup.
     */
    public static class Indexing {

      /**
       * The redelivery policy name (see {@code ingestion.domain.RedeliveryPolicy}). Held as a
       * string so the global {@code config} package does not import the {@code ingestion.domain}
       * enum; the {@code IngestionConfiguration} bean maps it to the domain policy.
       */
      private String redeliveryPolicy = "skip";

      /**
       * @return the redelivery policy name (default {@code "skip"})
       */
      public String getRedeliveryPolicy() {
        return redeliveryPolicy;
      }

      /**
       * @param redeliveryPolicy the redelivery policy name ({@code "skip"} or {@code "update"})
       */
      public void setRedeliveryPolicy(String redeliveryPolicy) {
        this.redeliveryPolicy = redeliveryPolicy;
      }
    }
  }

  /**
   * Deterministic-chunking parameters (S10-09), bound from {@code agentflow.ingestion.chunking.*}.
   * These back the {@code Chunker} the ingestion worker runs on extracted text. As with the
   * extraction limits, the values live in configuration, not code: the per-chunk character budget
   * and the context overlap are tuned here, and the {@code ChunkingConfig} constructor rejects a
   * nonsensical combination at bean creation (startup), mirroring the {@link Ingestion} group. The
   * strategy is held as a plain string on purpose &mdash; the global {@code config} package must
   * not import the {@code ingestion.domain} enum (that edge would cycle back through {@code
   * ingestion}), so the {@code DocumentChunkingConfiguration} bean maps it to {@code
   * Strategy.CHARACTER_BOUNDARY}.
   *
   * <p>Two independent bounds:
   *
   * <ul>
   *   <li><b>max-chunk-chars</b> &mdash; the maximum characters retained per chunk (default 500).
   *       Chunks are packed word-boundary-aware up to this budget; a single word longer than it is
   *       kept whole as its own chunk.
   *   <li><b>overlap-chars</b> &mdash; the characters carried forward from the end of one chunk
   *       into the next (default 50), so context is not lost at the boundary. Must be &lt; {@code
   *       max-chunk-chars}.
   * </ul>
   */
  public static class Chunking {

    /** Maximum characters retained per chunk. Default 500; at least 1, a value below 1 fails. */
    @Min(value = 1, message = "agentflow.ingestion.chunking.max-chunk-chars must be at least 1")
    private int maxChunkChars = 500;

    /**
     * Characters carried forward from the end of one chunk into the next. Default 50; at least 0
     * and strictly less than {@code max-chunk-chars}.
     */
    @Min(value = 0, message = "agentflow.ingestion.chunking.overlap-chars must be at least 0")
    private int overlapChars = 50;

    /**
     * The chunking strategy name (see {@code Strategy}). Held as a string so the global config
     * package does not import the {@code ingestion.domain} enum; the {@code
     * DocumentChunkingConfiguration} bean maps it to the domain strategy.
     */
    private String strategy = "character-boundary";

    /**
     * @return the maximum characters retained per chunk (default {@code 500})
     */
    public int getMaxChunkChars() {
      return maxChunkChars;
    }

    /**
     * @param maxChunkChars the maximum characters retained per chunk (at least 1)
     */
    public void setMaxChunkChars(int maxChunkChars) {
      this.maxChunkChars = maxChunkChars;
    }

    /**
     * @return the characters carried forward from one chunk into the next (default {@code 50})
     */
    public int getOverlapChars() {
      return overlapChars;
    }

    /**
     * @param overlapChars the characters carried forward from one chunk into the next (at least 0)
     */
    public void setOverlapChars(int overlapChars) {
      this.overlapChars = overlapChars;
    }

    /**
     * @return the chunking strategy name (default {@code "character-boundary"})
     */
    public String getStrategy() {
      return strategy;
    }

    /**
     * @param strategy the chunking strategy name
     */
    public void setStrategy(String strategy) {
      this.strategy = strategy;
    }
  }
}
