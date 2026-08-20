package skillbill.application.goalrunner

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.GoalRunnerOperatorDecisionRequest
import skillbill.application.model.GoalRunnerOperatorDecisionResult
import skillbill.ports.goalrunner.GoalRunnerManifestStore

@Inject
class GoalOperatorDecisionService(
  private val manifestStore: GoalRunnerManifestStore,
) {
  fun record(request: GoalRunnerOperatorDecisionRequest): GoalRunnerOperatorDecisionResult {
    manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: return GoalRunnerOperatorDecisionResult.Rejected(
        request.issueKey,
        "No prepared goal exists for '${request.issueKey}'.",
      )
    return GoalRunnerOperatorDecisionResult.Rejected(
      request.issueKey,
      "Operator decisions over review remediation are removed; " +
        "the run advances to validate after one implement_fix round.",
    )
  }
}
