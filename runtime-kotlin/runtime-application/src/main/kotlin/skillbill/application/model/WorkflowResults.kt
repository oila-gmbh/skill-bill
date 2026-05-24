package skillbill.application.model

import skillbill.workflow.model.WorkflowContinueView
import skillbill.workflow.model.WorkflowResumeView
import skillbill.workflow.model.WorkflowSnapshotView
import skillbill.workflow.model.WorkflowSummaryView

/**
 * SKILL-52.1 — Typed application result models for `WorkflowService`.
 *
 * Mirrors `LearningResults.kt`. Every public `WorkflowService` method
 * returns one of these results instead of `Map<String, Any?>`. The
 * CLI/MCP adapters convert the typed result back to the wire-shape
 * ordered map via mappers in
 * `runtime-cli/src/main/kotlin/skillbill/cli/WorkflowCliResultMappers.kt`
 * and `runtime-mcp/src/main/kotlin/skillbill/mcp/WorkflowMcpResultMappers.kt`,
 * which delegate the field-order contract to
 * `skillbill.contracts.workflow.WorkflowContracts`.
 *
 * Each result models `status` implicitly via either an Ok or Error
 * sealed variant, so callers do not branch on string status fields.
 * The pre-existing on-wire `status` / `error` keys are reattached by
 * the adapter mappers, preserving golden byte-equivalence.
 */
sealed interface WorkflowOpenResult {
  data class Ok(
    val workflowId: String,
    val dbPath: String,
    val snapshot: WorkflowSnapshotView,
  ) : WorkflowOpenResult
  data class Error(val workflowId: String, val error: String) : WorkflowOpenResult
}

sealed interface WorkflowUpdateResult {
  data class Ok(
    val workflowId: String,
    val dbPath: String,
    val snapshot: WorkflowSnapshotView,
  ) : WorkflowUpdateResult
  data class Error(val workflowId: String, val error: String, val dbPath: String? = null) : WorkflowUpdateResult
}

sealed interface WorkflowGetResult {
  data class Ok(
    val workflowId: String,
    val dbPath: String,
    val snapshot: WorkflowSnapshotView,
  ) : WorkflowGetResult
  data class Error(val workflowId: String, val error: String, val dbPath: String) : WorkflowGetResult
}

data class WorkflowListResult(
  val dbPath: String,
  val workflowCount: Int,
  val workflows: List<WorkflowSummaryView>,
)

sealed interface WorkflowLatestResult {
  data class Ok(val dbPath: String, val summary: WorkflowSummaryView) : WorkflowLatestResult
  data class Error(val dbPath: String, val error: String) : WorkflowLatestResult
}

sealed interface WorkflowResumeResult {
  data class Ok(
    val workflowId: String,
    val dbPath: String,
    val resume: WorkflowResumeView,
  ) : WorkflowResumeResult
  data class Error(val workflowId: String, val error: String, val dbPath: String) : WorkflowResumeResult
}

/**
 * Continuation results split into the regular continue path and the
 * decomposition continuation paths (missing-workflow, blocked-subtask,
 * blocked-git, done-decomposition). Adapter mappers reattach the
 * exact on-wire shape that the previous `continuePayload()` helper
 * produced.
 *
 * `WorkflowContinueResult.Reopened` and `Blocked` carry the typed
 * `WorkflowContinueView` so the adapter can reconstruct the
 * wire-shape map via `WorkflowEngine.continueMap(view)`.
 *
 * Decomposition variants carry their own typed fields because their
 * shapes do not pass through `WorkflowEngine.continueMap`.
 */
sealed interface WorkflowContinueResult {
  val dbPath: String

  /** Regular continue path with a typed view; covers ok / blocked / done / already_running / reopened. */
  data class Standard(
    override val dbPath: String,
    val view: WorkflowContinueView,
  ) : WorkflowContinueResult

  data class UnknownWorkflow(
    override val dbPath: String,
    val workflowId: String,
  ) : WorkflowContinueResult

  data class DecompositionMissingSubtaskWorkflow(
    override val dbPath: String,
    val subtaskId: Int,
    val blockedReason: String,
  ) : WorkflowContinueResult

  data class DecompositionBlockedSubtask(
    override val dbPath: String,
    val workflowId: String,
    val issueKey: String,
    val subtaskId: Int,
    val subtaskSpecPath: String,
    val blockedReason: String,
  ) : WorkflowContinueResult

  data class DecompositionBlockedBranchStart(
    override val dbPath: String,
    val workflowId: String,
    val issueKey: String,
    val blockedReason: String,
  ) : WorkflowContinueResult

  data class DecompositionDone(
    override val dbPath: String,
    val workflowId: String,
    val issueKey: String,
    val decompositionStatus: String,
  ) : WorkflowContinueResult

  data class DecompositionBlockedGit(
    override val dbPath: String,
    val workflowId: String,
    val issueKey: String,
    val blockedReason: String,
  ) : WorkflowContinueResult

  /**
   * Decomposition decoration of [Standard]: a regular continuation that
   * was resolved through the decomposition parent. Carries the typed
   * continue view plus the extra `decomposition_subtask_id` /
   * `decomposition_subtask_spec_path` wire fields.
   */
  data class DecompositionStandard(
    override val dbPath: String,
    val view: WorkflowContinueView,
    val decompositionSubtaskId: Int,
    val decompositionSubtaskSpecPath: String,
  ) : WorkflowContinueResult

  /** Generic error wrapper (unused except for upstream framework errors). */
  data class Error(override val dbPath: String, val workflowId: String, val error: String) : WorkflowContinueResult
}
