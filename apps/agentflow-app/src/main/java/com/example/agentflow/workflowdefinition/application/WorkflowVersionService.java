package com.example.agentflow.workflowdefinition.application;

import com.example.agentflow.audit.application.AuditService;
import com.example.agentflow.audit.domain.AuditAction;
import com.example.agentflow.audit.domain.AuditResourceType;
import com.example.agentflow.config.AgentFlowProperties;
import com.example.agentflow.workflowdefinition.domain.InvalidGraphException;
import com.example.agentflow.workflowdefinition.domain.WorkflowDefinition;
import com.example.agentflow.workflowdefinition.domain.WorkflowGraph;
import com.example.agentflow.workflowdefinition.domain.WorkflowVersionStatus;
import com.example.agentflow.workflowdefinition.persistence.WorkflowDefinitionRepository;
import com.example.agentflow.workflowdefinition.persistence.WorkflowGraphCodec;
import com.example.agentflow.workflowdefinition.persistence.WorkflowGraphParser;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersion;
import com.example.agentflow.workflowdefinition.persistence.WorkflowVersionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Application use cases for workflow definition versions (S05-12).
 *
 * <p>The {@code workflowdefinition} module owns version creation, activation and reading. This
 * service is the single entry point: a version is created from a graph payload (parsed and
 * validated through the closed-set graph gate, S05-04/S05-05), stored as a {@link
 * {@code WorkflowVersion} with a checksum, then activated — at which point its {@code version}
 * number and {@code checksum} are pinned and it becomes immutable.
 *
 * <p>The {@link WorkflowGraphParser}/{@link WorkflowGraphCodec} are pure round-trip helpers (no
 * bean exists for them in this module, so they are constructed here, as in {@link
 * com.example.agentflow.workflowdefinition.persistence.WorkflowVersionRowMapper}); the two
 * repositories are optional beans (present only with a {@code DataSource}). A {@code null} bean
 * means "no database", so every use case first answers {@code 503} and never falls back to an
 * in-memory store (invariant — PostgreSQL is the source of truth).
 */
@Service
public class WorkflowVersionService {

  private final WorkflowDefinitionRepository definitionRepo;
  private final WorkflowVersionRepository versionRepo;
  private final AuditService auditService;
  private final WorkflowGraphParser parser;
  private final WorkflowGraphCodec codec;
  private final int maxBytes;

  @Autowired
  public WorkflowVersionService(
      @Autowired(required = false) WorkflowDefinitionRepository definitionRepo,
      @Autowired(required = false) WorkflowVersionRepository versionRepo,
      AuditService auditService,
      AgentFlowProperties properties) {
    this.definitionRepo = definitionRepo;
    this.versionRepo = versionRepo;
    this.auditService = auditService;
    this.parser = new WorkflowGraphParser(new ObjectMapper());
    this.codec = new WorkflowGraphCodec(new ObjectMapper());
    this.maxBytes = properties == null ? 0 : properties.getGraph().getMaxBytes();
  }

  /**
   * @return {@code true} if a database (both repositories) is wired — the API answers {@code 503}
   *     otherwise
   */
  public boolean isWired() {
    return definitionRepo != null && versionRepo != null;
  }

  /**
   * Creates a new {@link WorkflowVersionStatus#DRAFT} version of a definition (S05-12).
   *
   * <p>Validates the graph through the closed-set gate ({@link
   * WorkflowGraphValidator#validate(WorkflowGraph)}), serializes and size-bounds it, pins a {@code
   * checksum} over the stored bytes, assigns the next version number and inserts the draft. The
   * definition is looked up tenant-scoped first (a cross-tenant id is a 404), then the insert is
   * tenant-scoped too, so another tenant's definition is never written through here. The action is
   * audited as {@link AuditAction#CREATE} on the {@link AuditResourceType#WORKFLOW_DEFINITION}
   * resource, joined to the insert in one transaction.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param workflowId the parent definition id
   * @param actor the actor, e.g. an email (never a token)
   * @param rawGraphJson the raw graph payload to store as a new version
   * @return the created draft version
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} if no database,
   *     {@code 404} if the definition is not owned by the tenant, {@code 400} if the graph is
   *     invalid or oversized, {@code 409} if the definition is not writable
   */
  @Transactional
  public WorkflowVersionDto createVersion(
      UUID tenantId, UUID workflowId, String actor, String rawGraphJson) {
    requireRepository();
    WorkflowDefinition definition =
        definitionRepo
            .findByTenantAndId(tenantId, workflowId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "workflow definition '" + workflowId + "' not found"));
    WorkflowGraph graph;
    String graphJson;
    try {
      // parse validates through the closed-set graph gate (S05-04/S05-05) before returning; the
      // graph is client input and is treated as untrusted data, never executed
      graph = parser.parse(rawGraphJson);
      graphJson = codec.serializeForStorage(graph, maxBytes); // size-bounds before storage
    } catch (InvalidGraphException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "the workflow graph is invalid: " + e.getMessage());
    }
    String checksum = checksum(graphJson);
    Integer next = versionRepo.nextVersionNumber(tenantId, workflowId);
    Optional<WorkflowVersion> created =
        versionRepo.create(tenantId, workflowId, next, graphJson, checksum, actor);
    WorkflowVersion version =
        created.orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "workflow definition '" + workflowId + "' is not writable for this tenant"));
    auditService.record(
        tenantId, actor, AuditAction.CREATE, AuditResourceType.WORKFLOW_DEFINITION, version.id());
    return WorkflowVersionDto.from(version, codec);
  }

  /**
   * Activates a draft version, pinning its version number and checksum as immutable (S05-12 /
   * S05-14).
   *
   * <p>The version is looked up tenant-scoped (a cross-tenant or missing id is a {@code 404}, the
   * tenant filter being in the SQL, invariant 1), then verified to belong to {@code workflowId} —
   * the endpoint addresses it through its parent workflow, so a version of a different workflow is
   * {@code 404} under this workflow. If the version is already {@code ACTIVATED} the call is
   * <b>idempotent</b> (S05-14): it returns the current, immutable version with no error and records
   * <em>no</em> second audit row, so a client that retries activation after a lost response — the
   * activation committed but the reply never arrived — gets a clean success instead of a conflict.
   * Otherwise it applies the conditional {@code DRAFT → ACTIVATED} transition (a zero-row outcome
   * is a tampered checksum or a competing writer, so it is a permanent {@code 409}, not a harmless
   * retry — invariant 6), audits it once as {@link AuditAction#ACTIVATE}, and returns the activated
   * (now immutable) version.
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param workflowId the parent workflow the version must belong to (resource hierarchy)
   * @param versionId the version id to activate
   * @param actor the actor, e.g. an email (never a token)
   * @return the activated (now immutable) version; a repeat on an already-activated version returns
   *     that same version (idempotent)
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} if no database,
   *     {@code 404} if the version is not owned by the tenant or does not belong to {@code
   *     workflowId}, {@code 409 CONFLICT} if the {@code DRAFT → ACTIVATED} transition changes zero
   *     rows (checksum mismatch or a competing writer)
   */
  @Transactional
  public WorkflowVersionDto activateVersion(
      UUID tenantId, UUID workflowId, UUID versionId, String actor) {
    requireRepository();
    WorkflowVersion version =
        versionRepo
            .get(tenantId, versionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "workflow version '" + versionId + "' not found"));
    // The endpoint addresses the version through its parent workflow, so a version that belongs to
    // a different workflow is not-found under this workflow (not a silent activation of the wrong
    // version). The tenant filter above already guarantees the version is this tenant's.
    if (!version.workflowId().equals(workflowId)) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "workflow version '" + versionId + "' does not belong to workflow " + workflowId);
    }
    // Idempotent (S05-14): an already-activated version is immutable. Re-activation is a no-op that
    // returns the current version without error and without recording a second audit row.
    if (WorkflowVersionStatus.isActivated(version.status())) {
      return WorkflowVersionDto.from(version, codec);
    }
    int changed = versionRepo.activate(tenantId, versionId, version.checksum());
    if (changed == 0) {
      // A zero-row conditional update is not a harmless retry: the stored checksum did not match (a
      // tampered graph) or a competing writer flipped the row. Surface it as a permanent conflict —
      // an ambiguous outcome, never a silent success (invariant 6). The owning application layer
      // maps this invariant breach to a {@code 409} here, exactly as {@link #createVersion} maps
      // its {@code InvalidGraphException} to a {@code 400}: the global handler in {@code common}
      // stays free of module-specific domain types (root AGENTS.md), so the domain exception does
      // not cross the boundary — it is turned into a {@code ResponseStatusException} inline.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "workflow version '" + versionId + "' is not activatable (checksum mismatch or race)");
    }
    WorkflowVersion activated = versionRepo.get(tenantId, versionId).orElseThrow();
    auditService.record(
        tenantId,
        actor,
        AuditAction.ACTIVATE,
        AuditResourceType.WORKFLOW_DEFINITION,
        activated.id());
    return WorkflowVersionDto.from(activated, codec);
  }

  /**
   * Reads a version by id, tenant scoped (S05-12).
   *
   * @param tenantId the owning tenant (from the verified security context)
   * @param versionId the version id
   * @return the version
   * @throws org.springframework.web.server.ResponseStatusException {@code 503} if no database,
   *     {@code 404} if the version is not owned by the tenant
   */
  public WorkflowVersionDto getVersion(UUID tenantId, UUID versionId) {
    requireRepository();
    WorkflowVersion version =
        versionRepo
            .get(tenantId, versionId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "workflow version '" + versionId + "' not found"));
    return WorkflowVersionDto.from(version, codec);
  }

  private void requireRepository() {
    if (!isWired()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "workflow definition versioning requires a database");
    }
  }

  /**
   * The checksum pinned on a version: the SHA-256 of the stored {@code graphJson} text, hex
   * encoded. It is computed over exactly what is written to {@code graph_json}, so it proves the
   * stored bytes have not changed since creation.
   *
   * @param graphJson the stored graph JSON
   * @return the hex SHA-256 of the stored graph JSON
   */
  static String checksum(String graphJson) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(graphJson.getBytes(StandardCharsets.UTF_8));
      return toHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the version checksum", e);
    }
  }

  /**
   * Encodes bytes as a lowercase hexadecimal string.
   *
   * @param hash the bytes to encode
   * @return the lowercase hex representation (twice the byte length)
   */
  private static String toHex(byte[] hash) {
    char[] hex = new char[hash.length * 2];
    for (int i = 0; i < hash.length; i++) {
      int value = hash[i] & 0xFF;
      hex[i * 2] = "0123456789abcdef".charAt(value >> 4);
      hex[i * 2 + 1] = "0123456789abcdef".charAt(value & 0x0F);
    }
    return new String(hex);
  }
}
