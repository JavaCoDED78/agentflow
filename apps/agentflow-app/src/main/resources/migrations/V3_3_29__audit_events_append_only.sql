-- S12-08 · audit_events · owner: audit
--
-- Append-only enforcement at the storage layer (the "DB policy" of S12-08).
--
-- The application write path (audit.application.AuditService) only ever INSERTs,
-- but the guarantee must survive the connection role: an ordinary application
-- role -- even one that happens to carry UPDATE / DELETE -- must not be able to
-- rewrite or delete a history row. A trigger enforces that at the data layer,
-- independently of role-level privileges, so "an ordinary application role
-- cannot rewrite an old audit record" (S12-08 acceptance) holds at every layer.
--
-- Reconciliation after a crash or an ambiguous external outcome appends a
-- corrected row (outcome FAILED) -- an INSERT, which the trigger allows. A past
-- row is never UPDATEd, so append-only (invariant 3) holds after recovery too.
--
-- A trigger FUNCTION is used rather than a GRANT alone: the application role
-- owns this table, and an owner holds UPDATE / DELETE by ownership -- GRANT /
-- REVOKE cannot remove them from an owner. The trigger overrides that.
CREATE OR REPLACE FUNCTION audit_events_prevent_rewrite()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % operations are not allowed', TG_OP;
    RETURN NULL;
END;
$$;

CREATE OR REPLACE TRIGGER audit_events_append_only_trg
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW
    EXECUTE FUNCTION audit_events_prevent_rewrite();

-- Least-privilege hardening (defense in depth). In a production-like deployment
-- (application-kubernetes.yml) the application SHOULD connect as a dedicated,
-- least-privilege role that holds only INSERT and SELECT on audit_events.
-- PUBLIC never holds these privileges here; the REVOKE makes the intent explicit
-- and guards against a future mis-grant. The trigger above is the real guarantee
-- against a role that owns the table.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_events FROM PUBLIC;

COMMENT ON FUNCTION audit_events_prevent_rewrite() IS
    'S12-08 · audit · append-only guard, raises on any UPDATE/DELETE of audit_events so a role cannot rewrite history.';

COMMENT ON TRIGGER audit_events_append_only_trg ON audit_events IS
    'S12-08 · audit · storage-level append-only, blocks rewrite/delete independent of role privileges.';

-- Retention (S12-08). Audit events are an accountability log, so they are not
-- deleted by the application: retention is a compliance decision, and the purge
-- of records older than the window is an ops-only, audited action performed by a
-- privileged operator role (never the application role) -- the follow-up ops
-- surface (S12-11 ... S12-13). Until that purge exists the trail grows; an old
-- row can still only be reached, never changed, by the application role.
--
-- Redaction (S12-08, see S12-09). metadata_redacted carries only the structural
-- identity of what ran (tool=<name>:<version>, step=<key>, actor=<subject>, and
-- never a token, API key, provider header, raw document text, full prompt
-- or an unredacted argument map). Redaction is applied by the caller before
-- record(...) (audit-contract section 6); audit stores only what it is given.
