package skillbill.ports.goalrunner

import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy

interface GoalRunnerControlRepository {
  fun controlState(parentWorkflowId: String): GoalRunnerControlState

  fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState

  fun clearControlState(parentWorkflowId: String)

  fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy?

  fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy

  fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance>

  fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance

  fun clearOutOfBandAcceptances(parentWorkflowId: String)
}

object EmptyGoalRunnerControlRepository : GoalRunnerControlRepository {
  private const val NAME = "EmptyGoalRunnerControlRepository"

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "controlState(parentWorkflowId=$parentWorkflowId)")
    return GoalRunnerControlState()
  }

  override fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "persistControlState(parentWorkflowId=$parentWorkflowId)")
    return state
  }

  override fun clearControlState(parentWorkflowId: String) {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "clearControlState(parentWorkflowId=$parentWorkflowId)")
  }

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "reviewPolicy(parentWorkflowId=$parentWorkflowId)")
    return null
  }

  override fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "persistReviewPolicy(parentWorkflowId=$parentWorkflowId)")
    return policy
  }

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "outOfBandAcceptances(parentWorkflowId=$parentWorkflowId)")
    return emptyMap()
  }

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "persistOutOfBandAcceptance(parentWorkflowId=$parentWorkflowId)")
    return acceptance
  }

  override fun clearOutOfBandAcceptances(parentWorkflowId: String) {
    RecordingNullObjectDiagnostics.recordSwallow(NAME, "clearOutOfBandAcceptances(parentWorkflowId=$parentWorkflowId)")
  }
}

object UnavailableGoalRunnerControlRepository : GoalRunnerControlRepository {
  private fun refuse(): Nothing = error("Goal-runner control persistence is unavailable.")

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState = refuse()

  override fun persistControlState(parentWorkflowId: String, state: GoalRunnerControlState): GoalRunnerControlState =
    refuse()

  override fun clearControlState(parentWorkflowId: String) = refuse()

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? = refuse()

  override fun persistReviewPolicy(parentWorkflowId: String, policy: GoalRunnerReviewPolicy): GoalRunnerReviewPolicy =
    refuse()

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> = refuse()

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance = refuse()

  override fun clearOutOfBandAcceptances(parentWorkflowId: String) = refuse()
}
