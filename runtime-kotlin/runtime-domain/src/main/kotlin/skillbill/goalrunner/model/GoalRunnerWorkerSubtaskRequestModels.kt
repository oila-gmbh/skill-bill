package skillbill.goalrunner.model

import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask

data class GoalRunnerWorkerSubtaskRequest(
  val name: String,
  val specPath: String,
  val rationale: String? = null,
  val dependsOnSubtaskIds: List<Int> = emptyList(),
  val requiresOperatorConfirmation: Boolean = false,
  val sourceStream: String = "unknown",
)

enum class GoalRunnerWorkerSubtaskRequestRejectionReason {
  MALFORMED,
  UNSAFE_PATH,
  DUPLICATE,
  UNKNOWN_DEPENDENCY,
  EMPTY_NAME,
}

sealed interface GoalRunnerWorkerSubtaskRequestOutcome {
  val sourceStream: String

  data class Accepted(
    val request: GoalRunnerWorkerSubtaskRequest,
    val subtask: DecompositionSubtask,
  ) : GoalRunnerWorkerSubtaskRequestOutcome {
    override val sourceStream: String = request.sourceStream
  }

  data class Queued(
    val request: GoalRunnerWorkerSubtaskRequest,
    val reason: String,
  ) : GoalRunnerWorkerSubtaskRequestOutcome {
    override val sourceStream: String = request.sourceStream
  }

  data class Rejected(
    override val sourceStream: String,
    val reason: GoalRunnerWorkerSubtaskRequestRejectionReason,
    val message: String,
  ) : GoalRunnerWorkerSubtaskRequestOutcome

  data class RequiresOperatorConfirmation(
    val request: GoalRunnerWorkerSubtaskRequest,
    val reason: String,
  ) : GoalRunnerWorkerSubtaskRequestOutcome {
    override val sourceStream: String = request.sourceStream
  }
}

data class GoalRunnerWorkerSubtaskSchedulingResult(
  val manifest: DecompositionManifest,
  val outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
)
