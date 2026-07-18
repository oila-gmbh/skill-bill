package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidWorkflowStateSchemaError
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Effect-free per-phase persistence and append-only phase ledger models. They ride
 * inside the workflow row's `artifacts_json` envelope. There is no clock, random, or
 * logging here: the application layer mints every timestamp/duration and passes it in.
 * Map decode loud-fails on malformed maps with a typed [InvalidWorkflowStateSchemaError]
 * and does no best-effort parsing. Ledger append/prune reuses the shared
 * `appendBoundedHistoryBySequence` helper at the durable write seam.
 */

const val FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY: String = "feature_task_runtime_phase_records"
const val FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY: String = "feature_task_runtime_phase_ledger"
const val FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY: String = "goal_planning_import"
const val FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT: Int = 200

data class FeatureTaskRuntimeGoalPlanningImport(
  val parentGoalWorkflowId: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val parentSpecHash: String,
  val decompositionManifestHash: String,
  val planningContractId: String,
  val planningContractVersion: String,
  val phaseOutputContractId: String,
  val phaseOutputContractVersion: String,
  val subtaskId: Int,
  val manifestOrder: Int,
  val governedSubSpecPath: String,
  val subSpecHash: String,
  val preplanPayloadSha256: String,
  val planPayloadSha256: String,
) {
  @OpenBoundaryMap("Validated goal-planning import provenance at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "source_kind" to "imported_goal_planning",
    "parent_goal_workflow_id" to parentGoalWorkflowId,
    "normalized_issue_key" to normalizedIssueKey,
    "repository_identity" to repositoryIdentity,
    "parent_spec_hash" to parentSpecHash,
    "decomposition_manifest_hash" to decompositionManifestHash,
    "planning_contract_id" to planningContractId,
    "planning_contract_version" to planningContractVersion,
    "phase_output_contract_id" to phaseOutputContractId,
    "phase_output_contract_version" to phaseOutputContractVersion,
    "subtask_id" to subtaskId,
    "manifest_order" to manifestOrder,
    "governed_sub_spec_path" to governedSubSpecPath,
    "sub_spec_hash" to subSpecHash,
    "preplan_payload_sha256" to preplanPayloadSha256,
    "plan_payload_sha256" to planPayloadSha256,
  )
}

/**
 * Durable run-scoped resolved feature branch. The runtime resolves a non-default feature branch
 * before any file-mutating phase runs and persists it here exactly once, so resume re-attaches to
 * the same branch and never creates a duplicate or divergent one.
 */
const val FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY: String = "feature_task_runtime_resolved_branch"
const val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY: String = "goal_continuation"
const val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME_ARTIFACT_KEY: String = "goal_continuation_outcome"

/**
 * The durable resolved feature branch for one run. [branch] is the non-default feature branch the
 * run is pinned to; [baseBranch] is the branch it was created from (null when the run reused an
 * already-checked-out branch rather than creating one).
 */
data class FeatureTaskRuntimeResolvedBranch(
  val branch: String,
  val baseBranch: String? = null,
  val created: Boolean = false,
) {
  init {
    require(branch.isNotBlank()) { "FeatureTaskRuntimeResolvedBranch.branch must be non-blank." }
  }

  @OpenBoundaryMap("Feature-task-runtime resolved-branch artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "branch" to branch,
    "created" to created,
  ).apply {
    baseBranch?.let { put("base_branch", it) }
  }

  companion object {
    /** Strict decode; loud-fails on a missing or malformed required field. */
    @OpenBoundaryMap("Feature-task-runtime resolved-branch decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeResolvedBranch = FeatureTaskRuntimeResolvedBranch(
      branch = raw.requireStringField("branch"),
      baseBranch = raw.optionalStringField("base_branch"),
      created = raw.optionalBooleanField("created") ?: false,
    )
  }
}

data class FeatureTaskRuntimeGoalContinuationOutcome(
  val issueKey: String,
  val subtaskId: Int,
  val status: String,
  val workflowId: String,
  val commitSha: String? = null,
  val blockedReason: String? = null,
  val lastResumableStep: String,
  val finalizingAgentId: String? = null,
  val participatingAgentIds: List<String> = emptyList(),
) {
  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.issueKey must be non-blank." }
    require(subtaskId > 0) { "FeatureTaskRuntimeGoalContinuationOutcome.subtaskId must be positive." }
    require(status.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.status must be non-blank." }
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.workflowId must be non-blank." }
    require(lastResumableStep.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationOutcome.lastResumableStep must be non-blank."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime goal-continuation outcome artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "issue_key" to issueKey,
    "subtask_id" to subtaskId,
    "status" to status,
    "workflow_id" to workflowId,
    "last_resumable_step" to lastResumableStep,
    "participating_agent_ids" to participatingAgentIds,
  ).apply {
    commitSha?.let { put("commit_sha", it) }
    blockedReason?.let { put("blocked_reason", it) }
    finalizingAgentId?.let { put("finalizing_agent_id", it) }
  }

  companion object {
    /** Strict decode; loud-fails on a missing or malformed required field. New agent fields are additive-optional. */
    @OpenBoundaryMap("Feature-task-runtime goal-continuation outcome decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationOutcome =
      FeatureTaskRuntimeGoalContinuationOutcome(
        issueKey = raw.requireStringField("issue_key"),
        subtaskId = raw.requireIntField("subtask_id"),
        status = raw.requireStringField("status"),
        workflowId = raw.requireStringField("workflow_id"),
        commitSha = raw.optionalStringField("commit_sha"),
        blockedReason = raw.optionalStringField("blocked_reason"),
        lastResumableStep = raw.requireStringField("last_resumable_step"),
        finalizingAgentId = raw.optionalStringField("finalizing_agent_id"),
        participatingAgentIds = raw.optionalStringListField("participating_agent_ids"),
      )
  }
}

/**
 * Durable per-phase launch briefing store. The assembled briefing is persisted, keyed
 * by phase id, before the phase agent is launched, so it is a durable handoff a consumer
 * reads rather than dead computation. Each entry is the latest briefing for that phase.
 */
const val FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY: String = "feature_task_runtime_phase_briefings"

/** Terminal status persisted on a phase record that the runtime blocked on. */
const val FEATURE_TASK_RUNTIME_PHASE_STATUS_BLOCKED: String = "blocked"

/**
 * Durable per-phase record: one entry per phase id holding its latest persisted state.
 * `finishedAt`/`durationMillis`/`outputArtifact` are nullable because a phase may be
 * persisted while still running; a finished phase carries all three.
 *
 * `startedAt` is re-minted on every running transition so `durationMillis` measures only
 * the current run, never spanning the resume gap; `firstStartedAt` preserves the original
 * first-started timestamp across resumes. A phase the runtime blocked on persists a
 * terminal `blocked` status with [blockedReason] so blocked-ness survives ledger pruning.
 */
data class FeatureTaskRuntimePhaseRecord(
  val phaseId: String,
  val status: String,
  val attemptCount: Int,
  val startedAt: String,
  val firstStartedAt: String = startedAt,
  val finishedAt: String? = null,
  val durationMillis: Long? = null,
  val resolvedAgentId: String,
  val outputArtifact: String? = null,
  val blockedReason: String? = null,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  val fileManifestBefore: List<String> = emptyList(),
  val fileManifestAfter: List<String> = emptyList(),
  val fileManifestIntroduced: List<String> = emptyList(),
  /** Latest backward-edge context for the resume watermark: the loop and per-edge iteration. */
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val reviewPassNumber: Int? = null,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.phaseId must be non-blank." }
    require(status.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.status must be non-blank." }
    require(attemptCount >= 1) {
      "FeatureTaskRuntimePhaseRecord.attemptCount must be >= 1, was $attemptCount."
    }
    require(startedAt.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.startedAt must be non-blank." }
    require(firstStartedAt.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.firstStartedAt must be non-blank." }
    require(resolvedAgentId.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.resolvedAgentId must be non-blank." }
    durationMillis?.let { duration ->
      require(duration >= 0) { "FeatureTaskRuntimePhaseRecord.durationMillis must be non-negative, was $duration." }
    }
    edgeIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimePhaseRecord.edgeIteration must be >= 1 when present, was $iteration."
      }
    }
    reviewPassNumber?.let { pass ->
      require(phaseId == "review" && pass in 1..2) {
        "FeatureTaskRuntimePhaseRecord.reviewPassNumber must be 1 or 2 and present only for review."
      }
    }
  }

  @OpenBoundaryMap("Feature-task-runtime per-phase record artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "phase_id" to phaseId,
    "status" to status,
    "attempt_count" to attemptCount,
    "started_at" to startedAt,
    "first_started_at" to firstStartedAt,
    "resolved_agent_id" to resolvedAgentId,
  ).apply {
    finishedAt?.let { put("finished_at", it) }
    durationMillis?.let { put("duration_millis", it) }
    outputArtifact?.let { put("output_artifact", it) }
    blockedReason?.let { put("blocked_reason", it) }
    failureDisposition?.let { put("failure_disposition", it.wireValue) }
    if (fileManifestBefore.isNotEmpty()) put("file_manifest_before", fileManifestBefore)
    if (fileManifestAfter.isNotEmpty()) put("file_manifest_after", fileManifestAfter)
    if (fileManifestIntroduced.isNotEmpty()) put("file_manifest_introduced", fileManifestIntroduced)
    loopId?.let { put("loop_id", it) }
    edgeIteration?.let { put("edge_iteration", it) }
    reviewPassNumber?.let { put("review_pass_number", it) }
  }

  companion object {
    /** Strict decode; loud-fails on any missing or malformed required field. */
    @OpenBoundaryMap("Feature-task-runtime per-phase record decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePhaseRecord {
      val startedAt = raw.requireStringField("started_at")
      return FeatureTaskRuntimePhaseRecord(
        phaseId = raw.requireStringField("phase_id"),
        status = raw.requireStringField("status"),
        attemptCount = raw.requireIntField("attempt_count"),
        startedAt = startedAt,
        // Records written before first_started_at existed fall back to started_at.
        firstStartedAt = raw.optionalStringField("first_started_at") ?: startedAt,
        finishedAt = raw.optionalStringField("finished_at"),
        durationMillis = raw.optionalLongField("duration_millis"),
        resolvedAgentId = raw.requireStringField("resolved_agent_id"),
        outputArtifact = raw.optionalStringField("output_artifact"),
        blockedReason = raw.optionalStringField("blocked_reason"),
        failureDisposition = raw.optionalStringField("failure_disposition")?.let { value ->
          FeatureTaskRuntimeFailureDisposition.fromWireValue(value)
            ?: throw InvalidWorkflowStateSchemaError(
              "Feature-task-runtime artifact field 'failure_disposition' has unsupported value '$value'.",
            )
        },
        fileManifestBefore = raw.optionalStringListField("file_manifest_before"),
        fileManifestAfter = raw.optionalStringListField("file_manifest_after"),
        fileManifestIntroduced = raw.optionalStringListField("file_manifest_introduced"),
        loopId = raw.optionalStringField("loop_id"),
        edgeIteration = raw.optionalIntField("edge_iteration"),
        reviewPassNumber = raw.optionalIntField("review_pass_number"),
      )
    }
  }
}

enum class FeatureTaskRuntimeFailureDisposition(val wireValue: String, val retryOnResume: Boolean) {
  RETRYABLE("retryable", true),
  NON_RETRYABLE_POLICY_CONFLICT("non_retryable_policy_conflict", false),
  NEEDS_USER_ACTION("needs_user_action", false),
  PROCESS_FAILURE("process_failure", true),
  INVALID_OUTPUT("invalid_output", true),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureTaskRuntimeFailureDisposition? =
      entries.firstOrNull { it.wireValue == value }
  }
}

/** Actions for the append-only phase attempt/event ledger. */
enum class FeatureTaskRuntimePhaseLedgerAction(val wireValue: String) {
  START("start"),
  RESUME("resume"),
  RETRY("retry"),
  FIX_LOOP_ITERATION("fix_loop_iteration"),
  LOOP_EDGE("loop_edge"),
  BLOCKED("blocked"),
  COMPLETE("complete"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimePhaseLedgerAction = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidWorkflowStateSchemaError(
        "Unknown feature-task-runtime phase ledger action '$value'. " +
          "Allowed: ${entries.joinToString { it.wireValue }}.",
      )
  }
}

/**
 * One append-only phase ledger entry with a monotonic [sequenceNumber] and an
 * application-minted [timestamp].
 */
data class FeatureTaskRuntimePhaseLedgerEntry(
  val action: FeatureTaskRuntimePhaseLedgerAction,
  val sequenceNumber: Int,
  val timestamp: String,
  val phaseId: String,
  val attemptCount: Int,
  val resolvedAgentId: String? = null,
  val fixLoopIteration: Int? = null,
  val blockedReason: String? = null,
  /** Authoritative per-edge trail for a backward-edge re-entry, distinct from [attemptCount]. */
  val loopId: String? = null,
  val edgeIteration: Int? = null,
) {
  init {
    require(sequenceNumber >= 0) {
      "FeatureTaskRuntimePhaseLedgerEntry.sequenceNumber must be non-negative, was $sequenceNumber."
    }
    require(timestamp.isNotBlank()) { "FeatureTaskRuntimePhaseLedgerEntry.timestamp must be non-blank." }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseLedgerEntry.phaseId must be non-blank." }
    require(attemptCount >= 1) {
      "FeatureTaskRuntimePhaseLedgerEntry.attemptCount must be >= 1, was $attemptCount."
    }
    fixLoopIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimePhaseLedgerEntry.fixLoopIteration must be >= 1 when present, was $iteration."
      }
    }
    edgeIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimePhaseLedgerEntry.edgeIteration must be >= 1 when present, was $iteration."
      }
    }
  }

  @OpenBoundaryMap("Feature-task-runtime phase ledger entry artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "action" to action.wireValue,
    "sequence_number" to sequenceNumber,
    "timestamp" to timestamp,
    "phase_id" to phaseId,
    "attempt_count" to attemptCount,
  ).apply {
    resolvedAgentId?.let { put("resolved_agent_id", it) }
    fixLoopIteration?.let { put("fix_loop_iteration", it) }
    blockedReason?.let { put("blocked_reason", it) }
    loopId?.let { put("loop_id", it) }
    edgeIteration?.let { put("edge_iteration", it) }
  }

  companion object {
    /** Strict decode; loud-fails on any missing or malformed required field. */
    @OpenBoundaryMap("Feature-task-runtime phase ledger entry decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePhaseLedgerEntry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.fromWire(raw.requireStringField("action")),
        sequenceNumber = raw.requireIntField("sequence_number"),
        timestamp = raw.requireStringField("timestamp"),
        phaseId = raw.requireStringField("phase_id"),
        attemptCount = raw.requireIntField("attempt_count"),
        resolvedAgentId = raw.optionalStringField("resolved_agent_id"),
        fixLoopIteration = raw.optionalIntField("fix_loop_iteration"),
        blockedReason = raw.optionalStringField("blocked_reason"),
        loopId = raw.optionalStringField("loop_id"),
        edgeIteration = raw.optionalIntField("edge_iteration"),
      )
  }
}

// Strict field decoders. The optional variants return null only when the key is
// absent; a present-but-malformed value still loud-fails rather than defaulting.

internal fun Map<String, Any?>.requireStringField(key: String): String {
  val value = this[key]
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact map is missing required field '$key'.",
    )
  return (value as? String)?.takeIf(String::isNotBlank)
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a non-blank string.",
    )
}

internal fun Map<String, Any?>.optionalStringField(key: String): String? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return (this[key] as? String)?.takeIf(String::isNotBlank)
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a non-blank string when present.",
    )
}

internal fun Map<String, Any?>.requireIntField(key: String): Int {
  if (!containsKey(key) || this[key] == null) {
    throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact map is missing required integer field '$key'.",
    )
  }
  return this[key].asExactIntOrNull()
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to an integer.",
    )
}

private fun Map<String, Any?>.optionalIntField(key: String): Int? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return this[key].asExactIntOrNull()
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to an integer when present.",
    )
}

internal fun Map<String, Any?>.optionalStringListField(key: String): List<String> {
  if (!containsKey(key) || this[key] == null) {
    return emptyList()
  }
  val list = this[key] as? List<*>
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a list of strings when present.",
    )
  return list.map { element ->
    (element as? String)?.takeIf(String::isNotBlank)
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime artifact field '$key' must contain only non-blank strings.",
      )
  }
}

internal fun Map<String, Any?>.optionalBooleanField(key: String): Boolean? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return this[key] as? Boolean
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a boolean when present.",
    )
}

private fun Map<String, Any?>.optionalLongField(key: String): Long? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return this[key].asExactLongOrNull()
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact field '$key' must decode to a long integer when present.",
    )
}

private fun Any?.asExactIntOrNull(): Int? = asExactLongOrNull()?.let { value ->
  if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else null
}

private fun Any?.asExactLongOrNull(): Long? = when (this) {
  is Byte -> toLong()
  is Short -> toLong()
  is Int -> toLong()
  is Long -> this
  is BigInteger -> runCatching { longValueExact() }.getOrNull()
  is BigDecimal -> runCatching { longValueExact() }.getOrNull()
  is String -> toLongOrNull()
  else -> null
}
