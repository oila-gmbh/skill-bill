package skillbill.ports.goalrunner.runner

import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import java.nio.file.Path

interface GoalRunnerWorkflowOutcomeMutationStore {
  fun authoritativeOutcomes(issueKey: String): Map<Int, GoalRunnerStoredOutcome> = emptyMap()

  fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String> = emptySet(),
    gate: GoalRunnerReconcileGate = GoalRunnerReconcileGate(),
    repoRoot: Path? = null,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent? = null,
  ): String?

  fun reopenBlockedPhaseForOperatorResume(workflowId: String, preferredPhaseId: String, reason: String): Boolean
}
