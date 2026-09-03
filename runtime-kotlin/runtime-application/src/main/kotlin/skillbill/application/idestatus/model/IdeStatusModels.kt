package skillbill.application.idestatus.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.GOAL_PLANNING_WAVE_CAP
import skillbill.contracts.workflow.IDE_STATUS_CONTRACT_VERSION
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.ports.featuretask.model.FeatureTaskRouteScope
import java.nio.file.Path
import java.time.Instant

enum class IdeStatusWorkflowFamily(val wireValue: String) {
  FEATURE_TASK_RUNTIME("feature-task-runtime"),
  FEATURE_VERIFY("feature-verify"),
  FEATURE_GOAL("feature-goal"),
}

enum class IdeStatusLifecycleState(val wireValue: String) {
  ACTIVE("active"),
  PAUSED("paused"),
  BLOCKED("blocked"),
  FAILED("failed"),
  TERMINAL("terminal"),
  IDLE("idle"),
}

enum class IdeStatusFreshness(val wireValue: String) {
  FRESH("fresh"),
  STALE("stale"),
  UNKNOWN("unknown"),
}

const val IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH: Int = 512

enum class IdeStatusPauseReasonCode(val wireValue: String) {
  AWAITING_OPERATOR_DECISION("awaiting_operator_decision"),
  OPERATOR_REQUEST("operator_request"),
  STOP_AFTER_SUBTASK("stop_after_subtask"),
  OPERATOR_STOP("operator_stop"),
  RUNNER_INTERRUPTED("runner_interrupted"),
  ;

  companion object {
    fun fromWire(value: String?): IdeStatusPauseReasonCode? = entries.firstOrNull { it.wireValue == value }
  }
}

data class IdeStatusPauseReason(
  val code: IdeStatusPauseReasonCode,
  val label: String? = null,
) {
  init {
    require(label == null || label.isNotBlank()) {
      "IdeStatusPauseReason.label must be absent or non-blank."
    }
    require(label == null || label.length <= IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH) {
      "IdeStatusPauseReason.label must be bounded to $IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH characters."
    }
  }

  val awaitsOperatorDecision: Boolean
    get() = code == IdeStatusPauseReasonCode.AWAITING_OPERATOR_DECISION

  companion object {
    fun of(code: IdeStatusPauseReasonCode, label: String?): IdeStatusPauseReason {
      val trimmed = label?.trim()?.takeIf(String::isNotBlank)
      return IdeStatusPauseReason(code = code, label = trimmed?.let(::boundedPauseReasonLabel))
    }

    private fun boundedPauseReasonLabel(label: String): String =
      if (label.length <= IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH) {
        label
      } else {
        label.take(IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
      }

    private const val TRUNCATION_MARKER: String = "… [truncated]"
  }
}

enum class IdeStatusProblemCode(val wireValue: String) {
  MISSING_REPOSITORY_IDENTITY("missing_repository_identity"),
  ABSENT_DATABASE("absent_database"),
  NO_MATCHING_WORK("no_matching_work"),
  INCOMPATIBLE_RECORD("incompatible_record"),
  INVALID_REPOSITORY_INPUT("invalid_repository_input"),
  SCHEMA_INCOMPATIBLE("schema_incompatible"),
}

/** Selection-tier ranking used only inside the application selector (not on the wire). */
enum class IdeStatusSelectionTier {
  ACTIVE,
  PAUSED,
  BLOCKED,
  FAILED,
  RECENTLY_TERMINAL,
  IDLE,
  ;

  val rank: Int get() = ordinal
}

data class IdeStatusStep(
  val id: String,
  val label: String,
)

data class IdeStatusProgress(
  val completed: Int,
  val total: Int,
) {
  init {
    require(completed >= 0) { "completed must be non-negative." }
    require(total >= 0) { "total must be non-negative." }
  }
}

data class IdeStatusCurrentSubtask(
  val id: String,
  val startedAt: Instant? = null,
  val activeDurationMs: Long? = null,
  val activeDurationAsOf: Instant? = null,
)

/**
 * The model the current phase's child was launched with. [effort] is null when the model string
 * already carries it (Cursor's merged `model[effort=…]`) or when no effort was resolved.
 */
data class IdeStatusCurrentModel(
  val model: String,
  val effort: String? = null,
  /**
   * The phase the model belongs to. A goal's `current_step` is a goal-level label — often
   * `planning` — so without this the payload names a model whose phase appears nowhere in it.
   */
  val phaseId: String? = null,
) {
  init {
    require(model.isNotBlank()) { "currentModel.model must not be blank." }
    effort?.let { require(it.isNotBlank()) { "currentModel.effort must not be blank when present." } }
    phaseId?.let { require(it.isNotBlank()) { "currentModel.phaseId must not be blank when present." } }
  }
}

/** Goal planning progress mirrored from [skillbill.goalrunner.model.GoalPlanningStatusSnapshot]. */
data class IdeStatusPlanning(
  val state: GoalPlanningStatusState,
  val sharedPreplanPrepared: Boolean,
  val plannedSubtaskCount: Int,
  val totalSubtaskCount: Int,
  /** Wire-shaped subtask id; the goal projection carries it as an Int. */
  val currentPlanningSubtaskId: String? = null,
  /** Wire-shaped ids of the subtasks the current planning wave covers, in manifest order. */
  val planningWaveSubtaskIds: List<String> = emptyList(),
  val reason: String? = null,
) {
  init {
    require(plannedSubtaskCount >= 0) { "plannedSubtaskCount must be non-negative." }
    require(totalSubtaskCount >= 0) { "totalSubtaskCount must be non-negative." }
    require(planningWaveSubtaskIds.all(String::isNotBlank)) {
      "planningWaveSubtaskIds entries must not be blank."
    }
    require(planningWaveSubtaskIds.distinct().size == planningWaveSubtaskIds.size) {
      "planningWaveSubtaskIds must not repeat a subtask id."
    }
    require(planningWaveSubtaskIds.size <= GOAL_PLANNING_WAVE_CAP) {
      "planningWaveSubtaskIds must hold at most $GOAL_PLANNING_WAVE_CAP ids, was " +
        "${planningWaveSubtaskIds.size}."
    }
  }
}

/**
 * Controlled vocabulary for [IdeStatusCurrentPhaseExecution.kind]. Distinguishes semantic
 * loops/passes from gate runs, capped backward edges, and generic phase attempts.
 */
enum class IdeStatusCurrentPhaseExecutionKind(val wireValue: String) {
  PASS("pass"),
  SEMANTIC_LOOP("semantic_loop"),
  GATE_RUN("gate_run"),
  BOUNDED_EDGE("bounded_edge"),
  ATTEMPT("attempt"),
}

/**
 * Authoritative current-phase execution measure. [total] is set only for a meaningful bounded
 * edge cap; semantic loops, passes, gate runs, and attempts omit it.
 */
data class IdeStatusCurrentPhaseExecution(
  val phaseId: String,
  val kind: IdeStatusCurrentPhaseExecutionKind,
  val count: Int,
  val total: Int? = null,
) {
  init {
    require(phaseId.isNotBlank()) { "currentPhaseExecution.phaseId must not be blank." }
    require(count >= 1) { "currentPhaseExecution.count must be >= 1, was $count." }
    total?.let {
      require(kind == IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE) {
        "currentPhaseExecution.total is allowed only for kind=bounded_edge, was kind=${kind.wireValue}."
      }
      require(it >= 1) { "currentPhaseExecution.total must be >= 1 when present, was $it." }
    }
  }
}

data class IdeStatusProblem(
  val code: IdeStatusProblemCode,
  val message: String,
  @OpenBoundaryMap("Optional typed problem details bag on the IDE status wire problem object")
  val details: Map<String, Any?>? = null,
) {
  init {
    require(message.isNotBlank()) { "problem.message must not be blank." }
  }
}

/**
 * In-process selection candidate for IDE status precedence. Not a wire DTO.
 */
data class IdeStatusCandidate(
  val workflowId: String,
  val workflowFamily: IdeStatusWorkflowFamily,
  val issueKey: String?,
  val currentState: String,
  val lifecycleState: IdeStatusLifecycleState,
  val selectionTier: IdeStatusSelectionTier,
  val updatedAt: Instant,
  val startedAt: Instant?,
  val routeScope: FeatureTaskRouteScope? = null,
  val isGoalAuthoritative: Boolean = workflowFamily == IdeStatusWorkflowFamily.FEATURE_GOAL,
)

/** Result of resolving `--repo-root` into a canonical repository identity. */
sealed class IdeStatusRepositoryResolution {
  data class Ok(val identity: String, val repoRoot: Path) : IdeStatusRepositoryResolution()
  data class Invalid(val message: String) : IdeStatusRepositoryResolution()
  data class Missing(val message: String) : IdeStatusRepositoryResolution()
}

/**
 * Application-layer IDE status snapshot. [toStatusWireMap] is the schema-validated emit shape.
 */
data class IdeStatusSnapshot(
  val repositoryIdentity: String,
  val lifecycleState: IdeStatusLifecycleState,
  val currentStep: IdeStatusStep,
  val updatedAt: Instant,
  val freshness: IdeStatusFreshness,
  val summary: String,
  val issueKey: String? = null,
  val workflowId: String? = null,
  val workflowFamily: IdeStatusWorkflowFamily? = null,
  val progress: IdeStatusProgress? = null,
  val startedAt: Instant? = null,
  val currentSubtask: IdeStatusCurrentSubtask? = null,
  // Null default: optional context, so a snapshot whose current phase has no recorded model
  // stays wire-identical.
  val currentModel: IdeStatusCurrentModel? = null,
  // Null default: only projectGoal populates planning, so every other family stays wire-identical.
  val planning: IdeStatusPlanning? = null,
  // Null default: optional current-phase execution; omitted when absent so older snapshots stay
  // wire-identical and planning-only goals never duplicate planning counts here.
  val currentPhaseExecution: IdeStatusCurrentPhaseExecution? = null,
  // Null defaults: only projectGoal populates the pause signals, so every other family stays
  // wire-identical. pause_requested is never emitted as false for the same reason.
  val pauseRequested: Boolean? = null,
  val pausedAt: Instant? = null,
  val pauseReason: IdeStatusPauseReason? = null,
  // Execution time rather than wall clock since startedAt; see the contract's active_duration_ms.
  val activeDurationMs: Long? = null,
  val activeDurationAsOf: Instant? = null,
  val lastAgentActivityAt: Instant? = null,
  val lastAgentActivityLabel: AgentActivityLabel? = null,
  val problem: IdeStatusProblem? = null,
  val contractVersion: String = IDE_STATUS_CONTRACT_VERSION,
) {
  init {
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity must not be blank." }
    require(summary.isNotBlank()) { "summary must not be blank." }
    require(currentStep.id.isNotBlank() && currentStep.label.isNotBlank()) {
      "currentStep id/label must not be blank."
    }
  }

  @OpenBoundaryMap("IDE status snapshot wire map at the schema-validation emit seam")
  fun toStatusWireMap(): Map<String, Any?> = buildMap {
    put("contract_version", contractVersion)
    put("repository_identity", repositoryIdentity)
    issueKey?.takeIf(String::isNotBlank)?.let { put("issue_key", it) }
    workflowId?.takeIf(String::isNotBlank)?.let { put("workflow_id", it) }
    workflowFamily?.let { put("workflow_family", it.wireValue) }
    put("lifecycle_state", lifecycleState.wireValue)
    put(
      "current_step",
      linkedMapOf(
        "id" to currentStep.id,
        "label" to currentStep.label,
      ),
    )
    progress?.let {
      put(
        "progress",
        linkedMapOf(
          "completed" to it.completed,
          "total" to it.total,
        ),
      )
    }
    startedAt?.let { put("started_at", it.toString()) }
    putCurrentSubtask()
    putCurrentModel()
    planning?.let { put("planning", planningWireMap(it)) }
    putCurrentPhaseExecution()
    pauseRequested?.takeIf { it }?.let { put("pause_requested", true) }
    pausedAt?.let { put("paused_at", it.toString()) }
    putPauseReason()
    putActiveDuration()
    putAgentActivity()
    put("updated_at", updatedAt.toString())
    put("freshness", freshness.wireValue)
    put("summary", summary)
    putProblem()
  }

  private fun MutableMap<String, Any?>.putCurrentSubtask() {
    val subtask = currentSubtask ?: return
    put(
      "current_subtask",
      buildMap {
        put("id", subtask.id)
        subtask.startedAt?.let { put("started_at", it.toString()) }
        subtask.activeDurationMs?.let { put("active_duration_ms", it) }
        subtask.activeDurationAsOf?.let { put("active_duration_as_of", it.toString()) }
      },
    )
  }

  /**
   * Omitted entirely when the current phase recorded no model, so a snapshot without it stays
   * wire-identical. `effort` needs no blank guard here: [IdeStatusCurrentModel] rejects a blank one.
   */
  private fun MutableMap<String, Any?>.putCurrentModel() {
    val model = currentModel ?: return
    put(
      "current_model",
      buildMap {
        put("model", model.model)
        model.effort?.let { put("effort", it) }
        model.phaseId?.let { put("phase_id", it) }
      },
    )
  }

  /**
   * Omitted entirely when no reliable current-phase execution value exists, so older producers and
   * planning-only snapshots stay wire-identical.
   */
  private fun MutableMap<String, Any?>.putCurrentPhaseExecution() {
    val execution = currentPhaseExecution ?: return
    put(
      "current_phase_execution",
      buildMap {
        put("phase_id", execution.phaseId)
        put("kind", execution.kind.wireValue)
        put("count", execution.count)
        execution.total?.let { put("total", it) }
      },
    )
  }

  /** Both keys are optional and goal-family-only, so a snapshot without them stays wire-identical. */
  private fun MutableMap<String, Any?>.putPauseReason() {
    val reason = pauseReason ?: return
    put(
      "pause_reason",
      buildMap {
        put("code", reason.code.wireValue)
        reason.label?.let { put("label", it) }
      },
    )
  }

  private fun MutableMap<String, Any?>.putActiveDuration() {
    activeDurationMs?.let { put("active_duration_ms", it) }
    activeDurationAsOf?.let { put("active_duration_as_of", it.toString()) }
  }

  private fun MutableMap<String, Any?>.putAgentActivity() {
    val at = lastAgentActivityAt
    val label = lastAgentActivityLabel
    if (at == null || label == null) return
    put("last_agent_activity_at", at.toString())
    put("last_agent_activity_label", label.wireValue)
  }

  private fun MutableMap<String, Any?>.putProblem() {
    val reported = problem ?: return
    put(
      "problem",
      buildMap {
        put("code", reported.code.wireValue)
        put("message", reported.message)
        reported.details?.takeIf { it.isNotEmpty() }?.let { put("details", it) }
      },
    )
  }

  private fun planningWireMap(planning: IdeStatusPlanning): Map<String, Any?> = buildMap {
    put("state", planning.state.wireValue)
    put("shared_preplan_prepared", planning.sharedPreplanPrepared)
    put("planned_subtask_count", planning.plannedSubtaskCount)
    put("total_subtask_count", planning.totalSubtaskCount)
    planning.currentPlanningSubtaskId?.takeIf(String::isNotBlank)
      ?.let { put("current_planning_subtask_id", it) }
    planning.planningWaveSubtaskIds.takeIf { it.isNotEmpty() }
      ?.let { put("planning_wave_subtask_ids", it) }
    planning.reason?.takeIf(String::isNotBlank)?.let { put("reason", it) }
  }
}

data class IdeStatusRequest(
  val repoRoot: String,
  val dbOverride: String? = null,
  val observedAt: Instant? = null,
) {
  init {
    require(repoRoot.isNotBlank()) { "repoRoot is required." }
  }
}

data class IdeStatusResult(
  val snapshot: IdeStatusSnapshot,
  val exitCode: Int,
)
