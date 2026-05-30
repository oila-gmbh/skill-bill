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
