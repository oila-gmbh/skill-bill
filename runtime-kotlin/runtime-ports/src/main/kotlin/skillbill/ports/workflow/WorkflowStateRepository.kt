package skillbill.ports.workflow

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.issuekey.isWellFormedIssueKey
import skillbill.contracts.issuekey.malformedIssueKeyReason
import skillbill.contracts.workflow.FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.workflow.model.FeatureImplementSessionSummary
import skillbill.ports.workflow.model.FeatureTaskExecutionIdentity
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.FeatureVerifySessionSummary
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toPayload
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.engine.model.WorkflowStateSnapshot

/**
 * Durable workflow-state persistence, split into one capability interface per
 * family so no single interface crosses the detekt `TooManyFunctions`
 * threshold. This remains the single port adapters implement.
 */
interface WorkflowStateRepository :
  FeatureTaskWorkflowStateRepository,
  GoalChildWorkflowStateRepository,
  FeatureTaskRuntimeWorkerRepository,
  FeatureImplementWorkflowStateRepository,
  FeatureVerifyWorkflowStateRepository,
  FeatureTaskRuntimeWorkflowStateRepository

interface FeatureTaskExecutionLookupRepository {
  fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity)

  fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    error("Feature-task execution identity lookup is not implemented by this persistence adapter.")

  fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate>

  fun findGoalChildFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ): List<FeatureTaskWorkflowCandidate> =
    error("Goal-child feature-task lookup is not implemented by this persistence adapter.")

  fun countGoalChildIdentities(normalizedIssueKey: String): Int = 0

  fun claimFeatureTaskContinuation(workflowId: String, expectedUpdatedAt: String?): Boolean =
    error("Feature-task continuation claiming is not implemented by this persistence adapter.")
}

interface FeatureTaskWorkflowRowRepository {
  fun terminalizeLegacyProseFeatureTaskWorkflow(row: WorkflowStateRecord): Unit =
    error("Legacy prose feature-task terminalization is not implemented by this persistence adapter.")

  fun saveFeatureTaskWorkflow(row: WorkflowStateRecord, mode: FeatureTaskWorkflowMode) {
    when (mode) {
      FeatureTaskWorkflowMode.PROSE ->
        (this as FeatureImplementWorkflowStateRepository).saveFeatureImplementWorkflow(row)
      FeatureTaskWorkflowMode.RUNTIME ->
        (this as FeatureTaskRuntimeWorkflowStateRepository).saveFeatureTaskRuntimeWorkflow(row)
    }
  }

  fun getFeatureTaskWorkflow(workflowId: String): WorkflowStateRecord? =
    (this as FeatureImplementWorkflowStateRepository).getFeatureImplementWorkflow(workflowId)
      ?: (this as FeatureTaskRuntimeWorkflowStateRepository).getFeatureTaskRuntimeWorkflow(workflowId)

  fun getFeatureTaskWorkflowAsMode(workflowId: String, mode: FeatureTaskWorkflowMode): WorkflowStateRecord? =
    when (mode) {
      FeatureTaskWorkflowMode.PROSE -> (this as FeatureImplementWorkflowStateRepository).getFeatureImplementWorkflow(
        workflowId,
      )
      FeatureTaskWorkflowMode.RUNTIME ->
        (this as FeatureTaskRuntimeWorkflowStateRepository).getFeatureTaskRuntimeWorkflow(workflowId)
    }

  fun listFeatureTaskWorkflows(mode: FeatureTaskWorkflowMode, limit: Int = 20): List<WorkflowStateRecord> =
    when (mode) {
      FeatureTaskWorkflowMode.PROSE -> (this as FeatureImplementWorkflowStateRepository).listFeatureImplementWorkflows(
        limit,
      )
      FeatureTaskWorkflowMode.RUNTIME ->
        (this as FeatureTaskRuntimeWorkflowStateRepository).listFeatureTaskRuntimeWorkflows(limit)
    }

  fun latestFeatureTaskWorkflow(mode: FeatureTaskWorkflowMode): WorkflowStateRecord? = when (mode) {
    FeatureTaskWorkflowMode.PROSE ->
      (this as FeatureImplementWorkflowStateRepository).latestFeatureImplementWorkflow()
    FeatureTaskWorkflowMode.RUNTIME ->
      (this as FeatureTaskRuntimeWorkflowStateRepository).latestFeatureTaskRuntimeWorkflow()
  }
}

interface FeatureTaskWorkflowStateRepository :
  FeatureTaskExecutionLookupRepository,
  FeatureTaskWorkflowRowRepository

interface GoalChildWorkflowStateRepository {
  fun deleteGoalChildWorkflowsByParent(parentWorkflowId: String): Int =
    error("Goal-child workflow deletion is not implemented by this persistence adapter.")

  fun deleteGoalChildWorkflow(
    parentWorkflowId: String,
    subtaskId: Int,
    workflowId: String,
    scope: GoalChildWorkflowDeletionScope = GoalChildWorkflowDeletionScope.TERMINAL_ONLY,
  ): Int = error("Scoped goal-child workflow deletion is not implemented by this persistence adapter.")
}

interface FeatureImplementWorkflowStateRepository {
  /**
   * Compatibility alias for bill-feature-task mode=prose. Authoritative
   * implementations should store the row in the shared feature-task workflow store.
   */
  fun saveFeatureImplementWorkflow(row: WorkflowStateRecord)

  fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureImplementWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    workflowIds.mapNotNull { workflowId -> getFeatureImplementWorkflow(workflowId)?.let { workflowId to it } }.toMap()

  fun listFeatureImplementWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun latestFeatureImplementWorkflow(): WorkflowStateRecord?

  fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary?
}

interface FeatureVerifyWorkflowStateRepository {
  fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord)

  fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureVerifyWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    workflowIds.mapNotNull { workflowId -> getFeatureVerifyWorkflow(workflowId)?.let { workflowId to it } }.toMap()

  fun listFeatureVerifyWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun latestFeatureVerifyWorkflow(): WorkflowStateRecord?

  fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary?
}

/**
 * Persistence for the experimental feature-task-runtime pipeline. Per-phase
 * records and the append-only phase ledger ride inside the [WorkflowStateRecord]
 * artifacts envelope; there is intentionally no session-summary method.
 */
interface FeatureTaskRuntimeWorkflowStateRepository {
  /**
   * Compatibility alias for bill-feature-task mode=runtime. Authoritative
   * implementations should store the row in the shared feature-task workflow store.
   */
  fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord)

  fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord?

  fun getFeatureTaskRuntimeWorkflows(workflowIds: Set<String>): Map<String, WorkflowStateRecord> =
    workflowIds.mapNotNull { workflowId -> getFeatureTaskRuntimeWorkflow(workflowId)?.let { workflowId to it } }.toMap()

  fun listFeatureTaskRuntimeWorkflows(limit: Int = 20): List<WorkflowStateRecord>

  fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord?
}

fun WorkflowStateSnapshot.toRecord(): WorkflowStateRecord = WorkflowStateRecord(
  workflowId = workflowId,
  sessionId = sessionId,
  workflowName = workflowName,
  contractVersion = contractVersion,
  workflowStatus = workflowStatus,
  currentStepId = currentStepId,
  stepsJson = stepsJson,
  artifactsJson = artifactsJson,
  startedAt = startedAt,
  updatedAt = updatedAt,
  finishedAt = finishedAt,
  mode = mode?.let(FeatureTaskWorkflowMode::fromWireValue),
)

fun WorkflowFamily.save(repository: WorkflowStateRepository, record: WorkflowStateSnapshot) {
  saveRecord(repository, record.toRecord())
}

fun WorkflowFamily.saveRecord(repository: WorkflowStateRepository, record: WorkflowStateRecord) {
  when (this) {
    WorkflowFamily.VERIFY -> repository.saveFeatureVerifyWorkflow(record)
    WorkflowFamily.TASK_RUNTIME -> repository.saveFeatureTaskWorkflow(record, FeatureTaskWorkflowMode.RUNTIME)
  }
}

fun WorkflowFamily.get(repository: WorkflowStateRepository, workflowId: String): WorkflowStateSnapshot? = when (this) {
  WorkflowFamily.VERIFY -> repository.getFeatureVerifyWorkflow(workflowId)
  WorkflowFamily.TASK_RUNTIME ->
    repository.getFeatureTaskWorkflowAsMode(workflowId, FeatureTaskWorkflowMode.RUNTIME)
}?.toSnapshot()

fun WorkflowFamily.getAll(
  repository: WorkflowStateRepository,
  workflowIds: Set<String>,
): Map<String, WorkflowStateSnapshot> = buildMap {
  workflowIds.chunked(WORKFLOW_SNAPSHOT_BATCH_SIZE).forEach { batch ->
    val records = when (this@getAll) {
      WorkflowFamily.VERIFY -> repository.getFeatureVerifyWorkflows(batch.toSet())
      WorkflowFamily.TASK_RUNTIME -> repository.getFeatureTaskRuntimeWorkflows(batch.toSet())
    }
    records.forEach { (workflowId, record) -> put(workflowId, record.toSnapshot()) }
  }
}

fun WorkflowFamily.list(repository: WorkflowStateRepository, limit: Int): List<WorkflowStateSnapshot> = when (this) {
  WorkflowFamily.VERIFY -> repository.listFeatureVerifyWorkflows(limit)
  WorkflowFamily.TASK_RUNTIME -> repository.listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, limit)
}.map(WorkflowStateRecord::toSnapshot)

fun WorkflowFamily.latest(repository: WorkflowStateRepository): WorkflowStateSnapshot? = when (this) {
  WorkflowFamily.VERIFY -> repository.latestFeatureVerifyWorkflow()
  WorkflowFamily.TASK_RUNTIME -> repository.latestFeatureTaskWorkflow(FeatureTaskWorkflowMode.RUNTIME)
}?.toSnapshot()

@OpenBoundaryMap("Durable workflow session summary passthrough")
fun WorkflowFamily.sessionSummary(repository: WorkflowStateRepository, sessionId: String): Map<String, Any?> {
  if (sessionId.isBlank()) {
    return emptyMap()
  }
  return when (this) {
    WorkflowFamily.VERIFY -> repository.getFeatureVerifySessionSummary(sessionId)?.toPayload().orEmpty()
    WorkflowFamily.TASK_RUNTIME -> emptyMap()
  }
}

const val WORKFLOW_SNAPSHOT_BATCH_SIZE = 900

object FeatureTaskExecutionIdentityPolicy {
  const val REPOSITORY_IDENTITY_PREFIX: String = "repo-root-realpath-v1:"

  private const val MAX_ECHOED_VALUE_LENGTH = 120

  fun validate(identity: FeatureTaskExecutionIdentity, sourceLabel: String = identity.workflowId) {
    val failure = when {
      identity.contractVersion != FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION ->
        "contract_version must be $FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION"
      identity.workflowId.isBlank() -> "workflow_id is malformed: expected a non-blank id"
      !isWellFormedIssueKey(identity.normalizedIssueKey) ->
        issueKeyFailure("normalized_issue_key", identity.normalizedIssueKey)
      !validRepositoryIdentity(identity.repositoryIdentity) ->
        repositoryIdentityFailure(identity.repositoryIdentity)
      !validGovernedSpecPath(identity.governedSpecPath) ->
        governedSpecPathFailure(identity.governedSpecPath)
      else -> null
    }
    failure?.let { throw InvalidFeatureTaskExecutionIdentitySchemaError(sourceLabel, it) }
  }

  fun normalizeIssueKey(issueKey: String, sourceLabel: String): String {
    if (!isWellFormedIssueKey(issueKey)) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        sourceLabel,
        issueKeyFailure("issue_key", issueKey),
      )
    }
    return issueKey.trim().uppercase()
  }

  fun validateLookupRequest(issueKey: String, repositoryIdentity: String): String {
    val normalizedIssueKey = normalizeIssueKey(issueKey, "lookup request")
    if (!validRepositoryIdentity(repositoryIdentity)) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "lookup request",
        repositoryIdentityFailure(repositoryIdentity),
      )
    }
    return normalizedIssueKey
  }

  private fun issueKeyFailure(field: String, value: String): String = malformedIssueKeyReason(field, echo(value))

  private fun repositoryIdentityFailure(value: String): String =
    "repository_identity is malformed: expected the prefix '$REPOSITORY_IDENTITY_PREFIX' followed by the " +
      "absolute real path of the Git top-level directory " +
      "(for example $REPOSITORY_IDENTITY_PREFIX/home/me/projects/app), but received ${echo(value)}"

  private fun governedSpecPathFailure(value: String): String =
    "governed_spec_path is malformed: expected a repository-relative '.feature-specs/<...>.md' path with no " +
      "empty, '.', or '..' segments, but received ${echo(value)}"

  private fun echo(value: String): String {
    val sanitized = value.replace("\r", "\\r").replace("\n", "\\n")
    val clipped = if (sanitized.length > MAX_ECHOED_VALUE_LENGTH) {
      sanitized.take(MAX_ECHOED_VALUE_LENGTH) + "..."
    } else {
      sanitized
    }
    return "'$clipped'"
  }

  private fun validRepositoryIdentity(value: String): Boolean = value.startsWith(REPOSITORY_IDENTITY_PREFIX) &&
    value.length > REPOSITORY_IDENTITY_PREFIX.length &&
    value.substring(REPOSITORY_IDENTITY_PREFIX.length).startsWith('/') &&
    value.none { it == '\r' || it == '\n' }

  private fun validGovernedSpecPath(value: String): Boolean = value.startsWith(".feature-specs/") &&
    value.endsWith(".md") &&
    value.none { it == '\r' || it == '\n' } &&
    value.split('/').none { it.isBlank() || it == "." || it == ".." }
}
