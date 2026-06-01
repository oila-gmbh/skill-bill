package skillbill.ports.goalrunner

import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.model.GoalRunnerObservabilityRecordRequest
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.model.GoalRunnerWorkflowProgress
import java.nio.file.Path

interface GoalRunnerManifestStore {
  fun loadByIssueKey(
    issueKey: String,
    dbPathOverride: String? = null,
    repoRoot: Path? = null,
  ): GoalRunnerManifestState?

  fun save(state: GoalRunnerManifestState, dbPathOverride: String? = null): GoalRunnerManifestState
}

interface GoalRunnerWorkflowOutcomeStore {
  fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String> = emptySet(),
    allowInactiveReconciliation: Boolean = true,
    dbPathOverride: String? = null,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun terminalOutcome(
    workflowId: String,
    issueKey: String,
    subtaskId: Int,
    dbPathOverride: String? = null,
  ): GoalRunnerStoredOutcome?

  fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent? = null,
    dbPathOverride: String? = null,
  ): String?

  fun progress(workflowId: String, dbPathOverride: String? = null): GoalRunnerWorkflowProgress?

  fun recordObservabilityEvent(request: GoalRunnerObservabilityRecordRequest, dbPathOverride: String? = null): Boolean

  fun recordWorkerSubtaskRequestOutcomes(
    workflowId: String,
    outcomes: List<GoalRunnerWorkerSubtaskRequestOutcome>,
    dbPathOverride: String? = null,
  ): Boolean
}

fun interface GoalRunnerSubtaskLauncher {
  fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome
}

fun interface GoalPullRequestPort {
  fun open(request: GoalPullRequestRequest): GoalPullRequestResult
}
