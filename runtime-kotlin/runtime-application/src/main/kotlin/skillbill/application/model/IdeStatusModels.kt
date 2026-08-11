package skillbill.application.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.IDE_STATUS_CONTRACT_VERSION
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.ports.persistence.model.FeatureTaskRouteScope
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
)

/**
 * The model the current phase's child was launched with. [effort] is null when the model string
 * already carries it (Cursor's merged `model[effort=…]`) or when no effort was resolved.
 */
data class IdeStatusCurrentModel(
  val model: String,
  val effort: String? = null,
) {
  init {
    require(model.isNotBlank()) { "currentModel.model must not be blank." }
    effort?.let { require(it.isNotBlank()) { "currentModel.effort must not be blank when present." } }
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
  val reason: String? = null,
) {
  init {
    require(plannedSubtaskCount >= 0) { "plannedSubtaskCount must be non-negative." }
    require(totalSubtaskCount >= 0) { "totalSubtaskCount must be non-negative." }
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
  // Null defaults: only projectGoal populates the pause signals, so every other family stays
  // wire-identical. pause_requested is never emitted as false for the same reason.
  val pauseRequested: Boolean? = null,
  val pausedAt: Instant? = null,
  // Execution time rather than wall clock since startedAt; see the contract's active_duration_ms.
  val activeDurationMs: Long? = null,
  val activeDurationAsOf: Instant? = null,
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
    currentSubtask?.let { subtask ->
      put(
        "current_subtask",
        buildMap {
          put("id", subtask.id)
          subtask.startedAt?.let { put("started_at", it.toString()) }
        },
      )
    }
    putCurrentModel()
    planning?.let { put("planning", planningWireMap(it)) }
    pauseRequested?.takeIf { it }?.let { put("pause_requested", true) }
    pausedAt?.let { put("paused_at", it.toString()) }
    putActiveDuration()
    put("updated_at", updatedAt.toString())
    put("freshness", freshness.wireValue)
    put("summary", summary)
    problem?.let { problem ->
      put(
        "problem",
        buildMap {
          put("code", problem.code.wireValue)
          put("message", problem.message)
          problem.details?.takeIf { it.isNotEmpty() }?.let { put("details", it) }
        },
      )
    }
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
      },
    )
  }

  /** Both keys are optional and goal-family-only, so a snapshot without them stays wire-identical. */
  private fun MutableMap<String, Any?>.putActiveDuration() {
    activeDurationMs?.let { put("active_duration_ms", it) }
    activeDurationAsOf?.let { put("active_duration_as_of", it.toString()) }
  }

  private fun planningWireMap(planning: IdeStatusPlanning): Map<String, Any?> = buildMap {
    put("state", planning.state.wireValue)
    put("shared_preplan_prepared", planning.sharedPreplanPrepared)
    put("planned_subtask_count", planning.plannedSubtaskCount)
    put("total_subtask_count", planning.totalSubtaskCount)
    planning.currentPlanningSubtaskId?.takeIf(String::isNotBlank)
      ?.let { put("current_planning_subtask_id", it) }
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
