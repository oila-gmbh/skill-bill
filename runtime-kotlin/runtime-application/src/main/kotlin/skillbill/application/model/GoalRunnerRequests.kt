package skillbill.application.model

import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_BYTES
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_HUNKS
import skillbill.ports.workflow.model.DEFAULT_SELECTED_DIFF_MAX_LINES
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.GoalSubtaskOperatorDecision
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class GoalRunnerRunRequest(
  val issueKey: String,
  val repoRoot: Path,
  val invokedAgentId: String,
  val configuredAgentOverrideId: String? = null,
  val dbPathOverride: String? = null,
  val timeout: Duration? = null,
  val progressIdleTimeout: Duration? = null,
  val planningBudget: Duration? = DEFAULT_GOAL_PLANNING_BUDGET,
  val outputSink: AgentRunOutputSink = AgentRunOutputSink.NONE,
  val eventSink: GoalRunnerEventSink = GoalRunnerEventSink.NONE,
  /** Null means reuse the parent goal's durable mode, or AUTO for a new parent. */
  val codeReviewMode: CodeReviewExecutionMode? = null,
  val agentAddonSelection: HydratedAgentAddonSelection = HydratedAgentAddonSelection(),
  val stopAfterSubtaskId: Int? = null,
  val observabilitySequenceStart: Int = DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(invokedAgentId.isNotBlank()) { "invokedAgentId is required." }
    configuredAgentOverrideId?.let { require(it.isNotBlank()) { "configuredAgentOverrideId must not be blank." } }
    stopAfterSubtaskId?.let { require(it > 0) { "stopAfterSubtaskId must be positive when provided." } }
    timeout?.let { maxWallClockTimeout ->
      require(maxWallClockTimeout.isPositive()) { "timeout must be positive when provided." }
    }
    progressIdleTimeout?.let { idleTimeout ->
      require(idleTimeout.isPositive()) { "progressIdleTimeout must be positive when provided." }
    }
    planningBudget?.let { budget ->
      require(budget.isPositive()) { "planningBudget must be positive when provided." }
    }
    require(observabilitySequenceStart >= 0) { "observabilitySequenceStart must be non-negative." }
  }
}

data class GoalRunnerPauseResult(
  val issueKey: String,
  val parentWorkflowId: String? = null,
  val status: String,
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(status.isNotBlank()) { "status is required." }
  }
}

/** Terminal outcomes of `goal stop`. Every one is reported; none falls back to killing by pid. */
enum class GoalRunnerStopStatus(val wireValue: String) {
  /** The owning runner process was contacted and terminated. */
  STOPPED("stopped"),

  /** The goal already carried an operator stop and no live lease remained. */
  ALREADY_STOPPED("already_stopped"),

  /** Durable stop intent was written, but no live runner process was holding the goal. */
  NO_LIVE_LEASE("no_live_lease"),

  /** The lease owner is on another host or boot, or the supervisor cannot identify it. Refused. */
  IDENTITY_MISMATCH("identity_mismatch"),

  /** No decomposed goal exists for the issue key. */
  NOT_FOUND("not_found"),
}

data class GoalRunnerStopVerbResult(
  val issueKey: String,
  val status: GoalRunnerStopStatus,
  val parentWorkflowId: String? = null,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val terminationAttempted: Boolean = false,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}

data class GoalRunnerResumeResult(
  val issueKey: String,
  val parentWorkflowId: String? = null,
  val status: String,
  val clearedPauseReason: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(status.isNotBlank()) { "status is required." }
  }
}

sealed interface GoalRunnerRunEvent {
  val issueKey: String

  data class Started(override val issueKey: String) : GoalRunnerRunEvent

  data class SubtaskStarted(
    override val issueKey: String,
    val subtaskId: Int,
    val action: String,
    // SKILL-64 Subtask 3 (AC24): authoritative durable step from the workflow
    // store, never a hardcoded local default. Null only before a durable step
    // exists for the child.
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskCompleted(
    override val issueKey: String,
    val subtaskId: Int,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskStopped(
    override val issueKey: String,
    val subtaskId: Int,
    val reason: String,
    val blockedReason: String,
    val currentStepId: String? = null,
  ) : GoalRunnerRunEvent

  data class SubtaskReviewSummary(
    override val issueKey: String,
    val subtaskId: Int,
    val passNumber: Int,
    val verdict: String,
    val findingCount: Int,
    val unresolvedFindingCount: Int,
    val findings: List<GoalSubtaskReviewCompactFinding>,
  ) : GoalRunnerRunEvent

  data class Completed(
    override val issueKey: String,
    val completedCount: Int,
    val pendingCount: Int,
    val blockedCount: Int,
    val pullRequestStatus: String,
    val pullRequestUrl: String?,
  ) : GoalRunnerRunEvent
}

// A goal-planning child writes no durable workflow rows while it runs, so the
// subtask progress-idle watchdog can never observe it making progress. Planning
// carries its own wall-clock budget instead.
val DEFAULT_GOAL_PLANNING_BUDGET: Duration = 30.minutes

const val DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START: Int = 10_000

// SKILL-64 Subtask 3 (AC16): goal_event: transition sequence space, distinct
// from the goal_observability sequence space (DEFAULT_GOAL_OBSERVABILITY_SEQUENCE_START).
const val DEFAULT_GOAL_EVENT_SEQUENCE_START: Int = 20_000

fun interface GoalRunnerEventSink {
  fun emit(event: GoalRunnerRunEvent)

  companion object {
    val NONE: GoalRunnerEventSink = GoalRunnerEventSink {}
  }
}

data class GoalRunnerStatusRequest(
  val issueKey: String,
  val invokedAgentId: String? = null,
  val configuredAgentOverrideId: String? = null,
  val dbPathOverride: String? = null,
  val repoRoot: Path? = null,
  val includeDiffStat: Boolean = false,
  val selectedDiffHunkPaths: List<String> = emptyList(),
  val selectedDiffMaxHunks: Int = DEFAULT_SELECTED_DIFF_MAX_HUNKS,
  val selectedDiffMaxLines: Int = DEFAULT_SELECTED_DIFF_MAX_LINES,
  val selectedDiffMaxBytes: Int = DEFAULT_SELECTED_DIFF_MAX_BYTES,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    invokedAgentId?.let { require(it.isNotBlank()) { "invokedAgentId must not be blank." } }
    configuredAgentOverrideId?.let { require(it.isNotBlank()) { "configuredAgentOverrideId must not be blank." } }
    require(selectedDiffHunkPaths.all { it.isNotBlank() }) { "selectedDiffHunkPaths must not contain blanks." }
    require(selectedDiffMaxHunks > 0) { "selectedDiffMaxHunks must be positive." }
    require(selectedDiffMaxLines > 0) { "selectedDiffMaxLines must be positive." }
    require(selectedDiffMaxBytes > 0) { "selectedDiffMaxBytes must be positive." }
  }
}

data class GoalRunnerResetRequest(
  val issueKey: String,
  val hard: Boolean,
  val preservePlanning: Boolean = false,
  val subtaskId: Int? = null,
  val deleteChildWorkflow: Boolean = false,
  val dbPathOverride: String? = null,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(!preservePlanning || hard) { "preservePlanning requires a hard reset." }
    require((subtaskId != null) == deleteChildWorkflow) {
      "subtaskId and deleteChildWorkflow must be supplied together."
    }
    require(subtaskId == null || subtaskId > 0) { "subtaskId must be positive." }
    require(!deleteChildWorkflow || !hard) { "Scoped child deletion is incompatible with a hard reset." }
    require(!deleteChildWorkflow || !preservePlanning) {
      "Scoped child deletion preserves planning intrinsically and cannot use preservePlanning."
    }
  }
}

data class GoalRunnerResetResult(
  val issueKey: String,
  val mode: String,
  val parentWorkflowId: String,
  val before: GoalRunnerResetSnapshot,
  val after: GoalRunnerResetSnapshot,
  val recovery: GoalRunnerChildRecoveryDiagnostic? = null,
)

data class GoalRunnerChildRecoveryDiagnostic(
  val subtaskId: Int,
  val workflowId: String,
  val classification: String,
  val recoveryCommand: String?,
)

data class GoalRunnerAcceptRequest(
  val issueKey: String,
  val subtaskId: Int,
  val commitSha: String,
  val reason: String,
  val dbPathOverride: String? = null,
  val repoRoot: Path? = null,
  val restoreAfterHardReset: Boolean = false,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(commitSha.isNotBlank()) { "commitSha is required." }
    require(reason.isNotBlank()) { "reason is required." }
  }
}

sealed interface GoalRunnerAcceptResult {
  data class Accepted(
    val issueKey: String,
    val parentWorkflowId: String,
    val subtaskId: Int,
    val commitSha: String,
    val reason: String,
    val acceptedAt: String,
    val after: GoalRunnerResetSnapshot,
  ) : GoalRunnerAcceptResult

  data class Rejected(val issueKey: String, val reason: String) : GoalRunnerAcceptResult
}

/**
 * Record an out-of-band operator decision on a paused goal child without hand-editing durable state
 * or `decomposition-manifest.yaml`. Resume later consumes the decision.
 */
data class GoalRunnerOperatorDecisionRequest(
  val issueKey: String,
  val subtaskId: Int,
  val decision: GoalSubtaskOperatorDecision,
  val dbPathOverride: String? = null,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
  }
}

sealed interface GoalRunnerOperatorDecisionResult {
  data class Recorded(
    val issueKey: String,
    val parentWorkflowId: String,
    val subtaskId: Int,
    val workflowId: String,
    val decision: String,
  ) : GoalRunnerOperatorDecisionResult

  data class Rejected(val issueKey: String, val reason: String) : GoalRunnerOperatorDecisionResult
}

internal sealed interface GoalRunnerAcceptanceEvidence {
  data class Resolved(val commitSha: String) : GoalRunnerAcceptanceEvidence
  data class Rejected(val reason: String) : GoalRunnerAcceptanceEvidence
}

data class GoalRunnerResetSnapshot(
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val subtasks: List<GoalRunnerResetSubtaskSnapshot>,
)

data class GoalRunnerResetSubtaskSnapshot(
  val id: Int,
  val status: String,
  val branch: String?,
  val workflowId: String?,
  val commitSha: String?,
  val blockedReason: String?,
  val lastResumableStep: String?,
)

data class GoalRunnerReplanRequest(
  val issueKey: String,
  val subtaskId: Int,
  val dbPathOverride: String? = null,
  val repoRoot: Path? = null,
  val includeSharedPreplan: Boolean = false,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId > 0) { "subtaskId must be positive." }
  }
}

data class GoalRunnerReplanResult(
  val issueKey: String,
  val parentWorkflowId: String,
  val subtaskId: Int,
  val discardedPlan: Boolean,
  val discardedSharedPreplan: Boolean = false,
  val cascadedPlanSubtaskIds: List<Int> = emptyList(),
  val clearedChildSubtaskIds: List<Int> = emptyList(),
  val before: GoalRunnerReplanSnapshot,
  val after: GoalRunnerReplanSnapshot,
)

data class GoalRunnerReplanSnapshot(
  val status: String,
  val currentSubtaskId: Int?,
  val currentAction: String,
  val sharedPreplanPrepared: Boolean,
  val plannedSubtaskIds: List<Int>,
  val subtasks: List<GoalRunnerResetSubtaskSnapshot>,
)

/** Wedge classes `goal repair` can diagnose and clear. */
enum class GoalRunnerWedgeClass(val wireValue: String, val durableField: String) {
  MISSING_VALIDATION_DEPTH("missing_validation_depth", "validation_depth"),
  MISSING_QUALITY_GATE_SELECTION("missing_quality_gate_selection", "quality_gate_selection"),
  UNREACHABLE_REVIEW_BASE("unreachable_review_base", "review_base_sha"),
  UNREACHABLE_REMEDIATION_BASE("unreachable_remediation_base", "remediation_base_sha"),
  STALE_BLOCKED_CONTINUATION_OUTCOME("stale_blocked_continuation_outcome", "goal_continuation_outcome"),
  COMPLETED_UPSTREAM_MISSING_OUTPUT("completed_upstream_missing_output", "phase_output"),
  PHASE_OUTPUT_CONTRACT_INCOMPATIBLE("phase_output_contract_incompatible", "phase_output_contract_version"),
  ;

  companion object {
    fun fromWire(value: String): GoalRunnerWedgeClass = entries.firstOrNull { it.wireValue == value }
      ?: error("Unknown goal-repair wedge class '$value'.")
  }

  val operatorRequired: Boolean
    get() = this == PHASE_OUTPUT_CONTRACT_INCOMPATIBLE
}

enum class GoalRunnerRepairStatus(val wireValue: String) {
  /** Inspect-only: at least one wedge was found; durable state untouched. */
  INSPECTED("inspected"),

  /** At least one wedge repair was applied. */
  REPAIRED("repaired"),

  /** Every inspected child passed every check; no durable write. */
  HEALTHY("healthy"),

  /** Apply was requested for a child that is not wedged. */
  NOT_WEDGED("not_wedged"),

  /** A targeted child holds a live worker lease. */
  LIVE_LEASE_REFUSED("live_lease_refused"),

  /**
   * At least one finding requires an operator hard reset; clearable wedges are not applied while
   * that blocker remains.
   */
  OPERATOR_REQUIRED("operator_required"),

  /** No decomposed goal exists for the issue key. */
  NOT_FOUND("not_found"),
}

data class GoalRunnerRepairRequest(
  val issueKey: String,
  val apply: Boolean = false,
  val subtaskId: Int? = null,
  val dbPathOverride: String? = null,
  val repoRoot: Path? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
    require(subtaskId == null || subtaskId > 0) { "subtaskId must be positive." }
  }
}

data class GoalRunnerWedgeFinding(
  val wedgeClass: GoalRunnerWedgeClass,
  val field: String,
  val currentValue: String?,
)

data class GoalRunnerChildWedgeDiagnosis(
  val subtaskId: Int,
  val workflowId: String?,
  val wedges: List<GoalRunnerWedgeFinding> = emptyList(),
  val passedChecks: List<String> = emptyList(),
) {
  val isHealthy: Boolean get() = wedges.isEmpty()
}

data class GoalRunnerChildRepairApplyResult(
  val repairs: List<GoalRunnerAppliedRepair> = emptyList(),
  val manifestProjectionArtifactsJson: String? = null,
)

data class GoalRunnerAppliedRepair(
  val subtaskId: Int,
  val workflowId: String,
  val wedgeClass: GoalRunnerWedgeClass,
  val field: String,
  val priorValue: String?,
  val newValue: String?,
)

data class GoalRunnerRepairResult(
  val issueKey: String,
  val status: GoalRunnerRepairStatus,
  val parentWorkflowId: String? = null,
  val diagnoses: List<GoalRunnerChildWedgeDiagnosis> = emptyList(),
  val appliedRepairs: List<GoalRunnerAppliedRepair> = emptyList(),
  val refusalReason: String? = null,
  val liveLeaseWorkflowId: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "issueKey is required." }
  }
}
