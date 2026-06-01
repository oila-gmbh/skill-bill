package skillbill.ports.goalrunner.model

import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.workflow.model.DecompositionManifest
import java.nio.file.Path

data class GoalRunnerManifestState(
  val parentWorkflowId: String,
  val dbPath: String,
  val manifest: DecompositionManifest,
)

data class GoalRunnerSubtaskLaunchRequest(
  val invokedAgentId: String,
  val configuredAgentOverrideId: String?,
  val skillRunRequest: SkillRunRequest,
)

data class GoalRunnerWorkflowProgress(
  val workflowId: String,
  val workflowStatus: String,
  val currentStepId: String,
  val progressToken: String,
  val latestDurableProgressEvent: GoalRunnerProgressEvent? = null,
  val latestGoalObservabilityEvent: GoalObservabilityProgressEvent? = null,
  val latestLivenessSignal: String? = null,
  val lastSnapshotUpdatedAt: String? = null,
)

data class GoalRunnerProgressEvent(
  val stepId: String,
  val attemptCount: Int,
  val kind: String,
  val message: String,
  val sequence: Int,
  val timestamp: String,
)

data class GoalObservabilityProgressEvent(
  val issueKey: String,
  val subtaskId: Int,
  val workflowPhase: String,
  val workerRole: String,
  val livenessClass: String,
  val activitySummary: String,
  val sequenceNumber: Int,
  val timestamp: String,
)

data class GoalPullRequestRequest(
  val repoRoot: Path,
  val issueKey: String,
  val featureName: String,
  val baseBranch: String,
  val headBranch: String,
  val title: String,
  val body: String,
)

sealed interface GoalPullRequestResult {
  data class Opened(val url: String) : GoalPullRequestResult
  data class Existing(val url: String) : GoalPullRequestResult
  data class Failed(val reason: String) : GoalPullRequestResult
}
