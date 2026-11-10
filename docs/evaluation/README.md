# Planner baseline eval cases

The fixed eval set every Planner prompt or model change is scored against (S09-14). The eval
measures **structure**, never text similarity: a plan is a typed graph to validate, not prose to
eyeball. One run answers "did this Planner configuration hold the baseline" with four per-case
metrics and a deterministic, diffable report.

## How it works

[`PlannerEvalRunner`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/evaluation/PlannerEvalRunner.java)
drives the production
[`PlannerService`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerService.java)
through the fixed cases — including the bounded repair (S09-08) and the pre-call budget guard
(S09-10) — and re-checks every proposed plan with the production
[`ValidationGate`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/ValidationGate.java)
(S09-07). The runner is pure and deterministic: no Spring, no I/O, no clock, no randomness. The
closed node vocabulary and the `PlanLimits` are constructor inputs (the caller owning the
workflow node registry supplies them; the `ai` module never compiles against it).

Each case produces four metrics:

| Metric | Passing condition |
|---|---|
| `schemaValid` | the plan parsed and passed the whole validation gate (shape, field contract, closed node vocabulary, tool allowlist, dependency DAG, limits) |
| `toolsWithinAllowlist` | every tool the plan references is in the case's allowlist |
| `expectedShapeMet` | exactly the expected step count and exactly the expected tool set |
| `withinLimits` | the step count respects the runner's `PlanLimits` |

A model attempt that fails (malformed JSON, schema mismatch, refusal, transport failure) is a
failed case carrying its
[`PlannerFailure`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/application/PlannerFailure.java)
classification — never a rethrown fault and never a silently skipped case.

## The baseline set

[`PlannerEvalCases.baseline()`](../../apps/agentflow-app/src/main/java/com/example/agentflow/ai/evaluation/PlannerEvalCases.java)
— fixed order, unmodifiable:

| Case | Shape under test | Allowlist | Expected |
|---|---|---|---|
| `calc-then-finalize` | minimal tool pipeline | `calculator` | 2 steps, tools `{calculator}` |
| `calc-report-finalize` | two-tool chain | `calculator`, `report` | 3 steps, tools `{calculator, report}` |
| `finalize-only` | no-tool answer, empty allowlist | — | 1 step, tools `{}` |
| `parallel-calc-finalize` | fork: two independent tool steps into one finalize | `calculator` | 3 steps, tools `{calculator}` |

Expectations are exact: a baseline compares against a known good answer, so "close enough" is not
a pass. Dependency wiring is covered by the schema-validity metric (the gate rejects dangling or
cyclic graphs), not pinned step-by-step, so step-id naming taste never fails a case.

## Running and comparing

The offline gate runs the whole eval suite against a scripted deterministic provider — no API key,
no network:

```bash
./gradlew :apps:agentflow-app:test --tests 'com.example.agentflow.ai.evaluation.*'
```

To compare a **prompt or model change** against the baseline, run the same `PlannerEvalRunner`
with a `PlannerService` wired to the changed prompt template or provider configuration and diff
the rendered report: `PlannerEvalReport.render()` is byte-stable (fixed `\n` line endings, no
timestamps, fixture-ordered), so any regression names the case, the metric and the gate
violations. An opt-in run against a real provider follows the same runner with a provider-backed
`PlannerService` (the provider wiring and its opt-in contract test are documented in
[../ai/real-provider-contract-test.md](../ai/real-provider-contract-test.md); the full runbook is
part of the AI integration guide, S09-15).

## Verification

The eval itself is pinned by
[`PlannerEvalRunnerTest`](../../apps/agentflow-app/src/test/java/com/example/agentflow/ai/evaluation/PlannerEvalRunnerTest.java)
(7 tests): the baseline passes against an ideal scripted model; a tool outside the allowlist, an
over-limit plan, a malformed response and an unexpected step count each fail exactly the metric
they violate; the rendered report is deterministic across runs.
