package skillbill.application.goalrunner

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.review.context.model.CodeReviewExecutionMode
import java.nio.file.Path

fun goalRepositoryIdentity(repoRoot: Path, repositoryEnclosingRootPort: RepositoryEnclosingRootPort): String =
  repositoryEnclosingRootPort.repositoryIdentity(repoRoot)

fun GoalRunnerManifestStore.effectiveAgentAddonSelection(
  parentWorkflowId: String,
  request: GoalRunnerRunRequest,
): AgentAddonSelection = request.agentAddonSelection.persisted
  .takeUnless { it.entries.isEmpty() }
  ?: reviewPolicy(parentWorkflowId, request.dbPathOverride)?.agentAddonSelection
  ?: AgentAddonSelection()

internal data class GoalRunnerEffectiveReviewPolicy(
  val codeReviewMode: CodeReviewExecutionMode,
)

internal fun effectiveGoalRunnerReviewPolicy(
  requestedReviewMode: CodeReviewExecutionMode?,
  persisted: GoalRunnerReviewPolicy?,
): GoalRunnerEffectiveReviewPolicy = GoalRunnerEffectiveReviewPolicy(
  codeReviewMode = requestedReviewMode
    ?: persisted?.codeReviewMode
    ?: CodeReviewExecutionMode.DEFAULT,
)

fun goalRunnerReviewPolicyMismatch(
  parentWorkflowId: String,
  requestedReviewMode: CodeReviewExecutionMode?,
  persisted: GoalRunnerReviewPolicy,
): String? = when {
  requestedReviewMode != null && persisted.codeReviewMode != requestedReviewMode ->
    "Cannot change code-review mode on goal resume: parent workflow '$parentWorkflowId' " +
      "is pinned to '${persisted.codeReviewMode.wireValue}', not '${requestedReviewMode.wireValue}'."
  else -> null
}
