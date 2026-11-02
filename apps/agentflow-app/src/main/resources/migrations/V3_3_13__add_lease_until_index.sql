-- S07-02 · workflow_runs · owner: workflowexecution
--
-- Backs the lease-recovery scan (ADR-011, S07-09): the recovery scheduler finds the runs whose
-- lease has expired (lease_until < now(), lease_owner still set, terminal runs skipped) and re-claims
-- them atomically. Without this index the scan is a Seq Scan over every run, which the operational
-- consequences in ADR-011 forbid; the index turns it into an index range scan on lease_until.
--
-- The index is on the lease column alone: the recovery scan is a lease_until range predicate, so a
-- single-column index backs it (database-conventions.md — one index per real lookup path). It is a
-- data-only lookup index and does not duplicate any constraint. The lease columns
-- (lease_owner / lease_until / lease_epoch) stay separate from the run's business state
-- (status / input_ref / result_ref / …), which is the S07-02 "do not mix lease state with business
-- state" rule.
--
-- IF NOT EXISTS keeps Testcontainers rebuilds and the controlled apply step quiet (database-migrations.md,
-- rule 5). Forward-only (ADR-008).
CREATE INDEX IF NOT EXISTS workflow_runs_lease_until_idx
    ON workflow_runs (lease_until);

COMMENT ON INDEX workflow_runs_lease_until_idx IS
    'S07-02 · workflowexecution · backs the lease-recovery scan (ADR-011, S07-09): lease_until < now() with lease_owner set finds expired leases for atomic re-claim without a full table scan; one index per lookup path (database-conventions.md). The lease columns stay separate from the run business state.';
