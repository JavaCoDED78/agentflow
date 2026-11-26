// Load scenario S14-12 — create-run + status polling.
//
// Measures the API path: POST /api/v1/runs (202) + GET /api/v1/runs/{runId}
// (200) until the run reaches a terminal state (SUCCEEDED / FAILED / CANCELLED)
// or the polling horizon elapses. One scenario, one fixed dataset: the same
// pinned workflow id, the same request shape per iteration — the only per-run
// difference is the unique idempotency key (an RFC 4122 v4 UUID, see uuidv4).
//
// No LLM is called: runs are created against an API with no worker attached
// (the README keeps the default OFF), so every run stays QUEUED and the polling
// loop is bounded by the horizon. A run executing steps that invoke a provider
// is an operator choice — this scenario never invokes a model.
//
// Configuration (env vars, CLI `-e`/`--env`):
//   BASE_URL          target API, default http://localhost:8080
//   WORKFLOW_ID       REQUIRED — the active workflow owned by the local tenant
//   VUS               constant-vus count, default 5
//   DURATION          scenario duration, default 60s
//   POLL_INTERVAL_SEC poll tick, default 0.5
//   MAX_POLLS         polling horizon in ticks, default 120 (~60s)
//
// Machine-readable output: run with --summary-export=<file>.json (see the
// README); the export carries every custom metric below, so terminal outcomes,
// rejected creates and the horizon are all present in the exported JSON.
//
// Metrics are load-test metrics only, not app telemetry — do not confuse them
// with the agentflow.* metric catalogue (docs/observability/metrics.md).

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const WORKFLOW_ID = __ENV.WORKFLOW_ID;
const VUS = Number(__ENV.VUS || '5');
const DURATION = __ENV.DURATION || '60s';
const POLL_INTERVAL_SEC = Number(__ENV.POLL_INTERVAL_SEC || '0.5');
const MAX_POLLS = Number(__ENV.MAX_POLLS || '120');
const TERMINAL_STATUSES = ['SUCCEEDED', 'FAILED', 'CANCELLED'];

if (!WORKFLOW_ID) {
  throw new Error(
    'WORKFLOW_ID is required — the scenario pins one active workflow owned by the local tenant. ' +
      'See docs/load-tests/README.md for the prerequisite (an active workflow must exist in the ' +
      'database; the HTTP API has no version-create endpoint, so seed it via the service layer or ' +
      'an integration test).'
  );
}

export const options = {
  scenarios: {
    'create-run-status': {
      executor: 'constant-vus',
      exec: 'createRunAndPoll',
      vus: VUS,
      duration: DURATION,
    },
  },
};

const createLatency = new Trend('agentflow_load_create_latency_ms');
const pollLatency = new Trend('agentflow_load_poll_latency_ms');
const createsTotal = new Counter('agentflow_load_creates_total');
const createsRejected = new Counter('agentflow_load_creates_rejected');
const pollsTotal = new Counter('agentflow_load_polls_total');
const terminalSucceeded = new Counter('agentflow_load_runs_succeeded');
const terminalFailed = new Counter('agentflow_load_runs_failed');
const terminalCancelled = new Counter('agentflow_load_runs_cancelled');
const runsHorizon = new Counter('agentflow_load_runs_horizon');

// RFC 4122 v4 UUID. Not a cryptographically secure PRNG — fine for an
// idempotency key, which only needs to be unique within a run, never secret.
function uuidv4() {
  return '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (c) => {
    const n = Number(c);
    return (n ^ (Math.floor(Math.random() * 16) & (15 >> (n / 4)))).toString(16);
  });
}

export function createRunAndPoll() {
  const idempotencyKey = uuidv4();
  const payload = JSON.stringify({ workflowId: WORKFLOW_ID });

  const created = http.post(`${BASE_URL}/api/v1/runs`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    tags: { operation: 'POST_runs' },
  });

  createLatency.add(created.timings.duration);
  createsTotal.add(1);
  const accepted = created.status === 202;
  check(created, {
    'create accepted (202)': (r) => r.status === 202,
  });

  let runId = null;
  if (accepted) {
    try {
      runId = created.json().id;
    } catch (e) {
      runId = null;
    }
  }

  if (!accepted || !runId) {
    createsRejected.add(1);
    return;
  }

  let finalStatus = null;
  for (let i = 0; i < MAX_POLLS; i++) {
    const status = http.get(`${BASE_URL}/api/v1/runs/${runId}`, {
      tags: { operation: 'GET_runs' },
    });
    pollLatency.add(status.timings.duration);
    pollsTotal.add(1);
    check(status, {
      'status read ok (200)': (r) => r.status === 200,
    });

    let runStatus = null;
    if (status.status === 200) {
      try {
        runStatus = status.json().status;
      } catch (e) {
        runStatus = null;
      }
    }

    if (runStatus && TERMINAL_STATUSES.includes(runStatus)) {
      finalStatus = runStatus;
      break;
    }

    if (status.status !== 200) {
      // The read path is broken for this run — stop polling; the run is counted
      // as a horizon (never claimed terminal).
      break;
    }

    sleep(POLL_INTERVAL_SEC);
  }

  if (finalStatus === 'SUCCEEDED') terminalSucceeded.add(1);
  else if (finalStatus === 'FAILED') terminalFailed.add(1);
  else if (finalStatus === 'CANCELLED') terminalCancelled.add(1);
  else runsHorizon.add(1);
}

export default function () {
  createRunAndPoll();
}
