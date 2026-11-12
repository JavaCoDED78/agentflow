package com.example.agentflow.ingestion.worker;

import com.example.agentflow.config.AgentFlowProperties;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.Lifecycle;
import org.springframework.stereotype.Component;

/**
 * The ingestion worker's poll-loop driver and its graceful-shutdown lifecycle (S10-13), the
 * ingestion mirror of {@code workflowexecution.worker.WorkerLifecycle} (S07-13).
 *
 * <p>{@link DocumentIndexingWorker} holds no loop of its own: from {@link #start()} this bean runs
 * one daemon thread that invokes {@link DocumentIndexingWorker#pollOnce()} on every cadence, and
 * from {@link #stop()} it performs the graceful-shutdown contract the plan mandates: a worker
 * <em>stops claiming new records</em> (the loop exits), <em>gives the in-flight record bounded
 * time</em> to finish (the sequential loop never abandons a record mid-pass), and then stops. The
 * bounded wait uses {@code agentflow.worker.shutdown.timeout-seconds}, the same ceiling the
 * workflow worker lifecycle uses.
 *
 * <p>Shutdown is bounded, not blocking forever: {@code stop()} clears the running flag and calls
 * {@link DocumentIndexingWorker#wakeup()} so a long poll returns immediately (an in-flight record
 * still completes — {@code wakeup} aborts the wait, never the processing), then joins the loop
 * thread up to the configured ceiling. The consumer is closed by the worker's own
 * {@code @PreDestroy}.
 *
 * <p>The bean is enabled only in the {@code ingestion-worker} role (the {@code
 * agentflow.worker.ingestion-loop-enabled} flag, {@code false} by default) and only when a {@link
 * DataSource} exists (the pipeline is database-backed); the {@code api} role and the DB-less
 * skeleton never start it, matching the worker's own gating.
 */
@Component
@ConditionalOnProperty(
    prefix = "agentflow.worker",
    name = "ingestion-loop-enabled",
    havingValue = "true")
@ConditionalOnBean(DataSource.class)
public class IngestionWorkerLifecycle implements Lifecycle {

  private static final Logger log = LoggerFactory.getLogger(IngestionWorkerLifecycle.class);

  private final DocumentIndexingWorker worker;
  private final AgentFlowProperties properties;

  private volatile Thread loopThread;
  private volatile boolean running;

  /**
   * @param worker the indexing worker this lifecycle drives; {@code null} when no worker bean is
   *     configured (the loop has nothing to run). {@code @Autowired(required = false)} so the
   *     constructor fails loudly when it is absent rather than starting a loop that serves nothing
   *     (the {@code WorkerLifecycle} precedent).
   * @param properties the validated worker configuration (shutdown ceiling)
   */
  public IngestionWorkerLifecycle(
      @Autowired(required = false) DocumentIndexingWorker worker, AgentFlowProperties properties) {
    if (worker == null) {
      throw new IllegalStateException(
          "ingestion worker lifecycle is unavailable: no DocumentIndexingWorker bean is"
              + " configured (S10-13) — the poll loop has nothing to drive");
    }
    this.worker = worker;
    this.properties = properties;
  }

  /** Starts the poll loop thread. Idempotent: a running loop is left untouched. */
  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    loopThread = new Thread(this::runLoop, "agentflow-ingestion-worker-loop");
    loopThread.setDaemon(true);
    loopThread.start();
    log.info(
        "ingestion indexing worker poll loop started (topic {})", DocumentIndexingWorker.TOPIC);
  }

  /**
   * The poll-loop body: one {@code pollOnce} pass, then the next poll (the timeout bounds the
   * shutdown-flag latency; {@code wakeup} interrupts the wait immediately).
   */
  private void runLoop() {
    while (running) {
      try {
        worker.pollOnce();
      } catch (RuntimeException pollFailure) {
        // A poll/handle fault must not kill the loop: log and continue (the record-level failures
        // are already classified and held by the inbox boundary; this is the transport level).
        log.warn("ingestion worker poll pass failed; continuing", pollFailure);
      }
    }
    log.debug("ingestion worker poll loop exited");
  }

  /**
   * Stops the loop gracefully: claim no new records (the running flag clears and the long poll is
   * woken), give the in-flight pass bounded time to finish, then return. The bounded wait is the
   * reason this blocks: it is the worker's commitment to let a record complete, capped so shutdown
   * never waits forever.
   */
  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    worker.wakeup();
    Thread thread = loopThread;
    if (thread != null) {
      long timeoutMs = properties.getWorker().getShutdownTimeoutSeconds() * 1000L;
      try {
        thread.join(timeoutMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (thread.isAlive()) {
        log.warn(
            "ingestion worker poll loop did not drain within {} ms; the in-flight pass is left to"
                + " finish on its own (its inbox transaction commits or rolls back durably)",
            timeoutMs);
      } else {
        log.info("ingestion worker poll loop drained and stopped");
      }
    }
  }

  /**
   * @return {@code true} while the poll loop thread is running.
   */
  @Override
  public boolean isRunning() {
    return running;
  }
}
