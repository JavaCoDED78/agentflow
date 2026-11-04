package com.example.agentflow.workflowexecution.worker;

import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowexecution.application.WorkflowWorker;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.Lifecycle;
import org.springframework.stereotype.Component;

/**
 * The worker poll-loop driver and its graceful-shutdown lifecycle (S07-13).
 *
 * <p>The {@link WorkflowWorker} is the durable event loop, but it holds no loop of its own and is
 * driven only by tests today. This bean owns that loop: from {@link #start()} it runs one thread
 * that invokes {@link WorkflowWorker#pollOnce(UUID)} for each configured tenant on every cadence,
 * and from {@link #stop()} it performs the graceful-shutdown contract the plan mandates (S07-13): a
 * worker <em>stops claiming new runs</em>, <em>gives the current step bounded time</em> to finish,
 * and <em>stops extending the run's lease (the heartbeat)</em>.
 *
 * <p>Shutdown is bounded, not blocking forever. {@code stop()} sets the worker's shutdown flag (so
 * {@code pollOnce} returns zero and the loop stops claiming) and {@linkplain Thread#join(long)
 * joins} the loop thread up to {@code agentflow.worker.shutdown.timeout-seconds}. When that ceiling
 * elapses the loop thread is left to run; its run-level lease then expires and the stale-run
 * recovery scheduler ({@code S07-09}) re-claims the run. Terention therefore never leaves a {@code
 * RUNNING} run indefinitely without recovery — the S07-13 acceptance criterion — because PostgreSQL
 * (not the worker) is the source of truth for the lease and recovery re-derives the owner on every
 * scan.
 *
 * <p>The bean is enabled only in the {@code workflow-worker} role (the {@code
 * agentflow.worker.loop-enabled} flag, {@code true} by default there) and only when a {@link
 * DataSource} exists (the loop touches the database); the {@code api} role and the DB-less skeleton
 * never start it, matching the recovery scheduler's own gating.
 *
 * <p>The tenant list is the {@code AgentFlowProperties.Worker#tenants} local-skeleton start-up set;
 * the production driver discovers tenants from the trusted identity layer (invariant 1), never from
 * a body, query or Kafka header.
 */
@Component
@ConditionalOnProperty(prefix = "agentflow.worker", name = "loop-enabled", havingValue = "true")
@ConditionalOnBean(DataSource.class)
public class WorkerLifecycle implements Lifecycle {

  private static final Logger log = LoggerFactory.getLogger(WorkerLifecycle.class);

  private final WorkflowWorker worker;
  private final AgentFlowProperties properties;

  private volatile Thread loopThread;
  private volatile boolean running;

  /**
   * @param worker the durable worker this lifecycle drives; {@code null} when no worker bean is
   *     configured (the loop has nothing to run). {@code @Autowired(required = false)} so the
   *     application still boots without a worker (S01-02); the constructor fails loudly when it is
   *     absent rather than starting a loop that serves nothing.
   * @param properties the validated worker configuration (loop cadence, drain ceiling, tenants)
   */
  public WorkerLifecycle(
      @Autowired(required = false) WorkflowWorker worker, AgentFlowProperties properties) {
    if (worker == null) {
      throw new IllegalStateException(
          "worker lifecycle is unavailable: no WorkflowWorker bean is configured "
              + "(S07-13) — the poll loop has nothing to drive");
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
    loopThread = new Thread(this::runLoop, "agentflow-worker-loop");
    loopThread.setDaemon(true);
    loopThread.start();
    log.info(
        "workflow worker poll loop started for {} tenant(s)",
        properties.getWorker().getTenants().size());
  }

  /** The poll-loop body: one {@code pollOnce} pass per configured tenant, then a fixed sleep. */
  private void runLoop() {
    long intervalMs = properties.getWorker().getPollIntervalMs();
    List<UUID> tenants = properties.getWorker().getTenants();
    while (running && !worker.isShuttingDown()) {
      for (UUID tenant : tenants) {
        if (running && !worker.isShuttingDown()) {
          worker.pollOnce(tenant);
        }
      }
      try {
        Thread.sleep(intervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    log.debug("workflow worker poll loop exited");
  }

  /**
   * Stops the loop gracefully (S07-13): claim nothing new, let the in-flight step finish within the
   * bounded drain, then stop. The bounded drain is the reason this blocks: it is the worker's
   * commitment to give a step a fair chance to complete, capped so shutdown never waits forever.
   */
  @Override
  public synchronized void stop() {
    if (!running) {
      return;
    }
    running = false;
    worker.stop(); // stop claiming new runs (S07-13); the in-flight run drains on its own
    Thread thread = loopThread;
    if (thread != null) {
      long timeoutMs = properties.getWorker().getShutdownTimeoutSeconds() * 1000L;
      try {
        thread.join(timeoutMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (thread.isAlive()) {
        // Bounded drain elapsed; the loop thread is left running. Its run-level lease is no longer
        // extended (heartbeat stopped) and expires, so the stale-run recovery scheduler re-claims
        // the run, so termination never leaves a RUNNING run without recovery (S07-13).
        log.warn(
            "worker poll loop did not drain within {} ms; the in-flight run lease is left to "
                + "expire (heartbeat stopped) so recovery re-claims it",
            timeoutMs);
      } else {
        log.info(
            "workflow worker poll loop drained and stopped; in-flight run lease is not "
                + "extended (heartbeat stopped) for {}",
            properties.getWorker().getTenants());
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
