package skillbill.ports.goalrunner.runner

import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.model.WorkflowId
import java.nio.file.Path

interface GoalRunnerWorkflowOutcomeMutationStore {
  fun authoritativeOutcomes(issueKey: IssueKey): Map<Int, GoalRunnerStoredOutcome> = emptyMap()

  fun reconcileAuthoritativeOutcomes(
    issueKey: IssueKey,
    activeWorkflowIds: Set<String> = emptySet(),
    gate: GoalRunnerReconcileGate = GoalRunnerReconcileGate(),
    repoRoot: Path? = null,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun markBlocked(
    workflowId: WorkflowId,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent? = null,
  ): String?

  fun reopenBlockedPhaseForOperatorResume(workflowId: WorkflowId, preferredPhaseId: String, reason: String): Boolean
}
