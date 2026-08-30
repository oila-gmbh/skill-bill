package skillbill.ports.goalrunner.runner

import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerStoredOutcome
import skillbill.ports.goalrunner.runner.model.GoalRunnerSupervisionEvent
import java.nio.file.Path

interface GoalRunnerWorkflowOutcomeMutationStore {
  fun authoritativeOutcomes(issueKey: String, dbPathOverride: String? = null): Map<Int, GoalRunnerStoredOutcome> =
    emptyMap()

  fun reconcileAuthoritativeOutcomes(
    issueKey: String,
    activeWorkflowIds: Set<String> = emptySet(),
    gate: GoalRunnerReconcileGate = GoalRunnerReconcileGate(),
    repoRoot: Path? = null,
    dbPathOverride: String? = null,
  ): Map<Int, GoalRunnerStoredOutcome>

  fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent? = null,
    dbPathOverride: String? = null,
  ): String?

  fun reopenBlockedPhaseForOperatorResume(
    workflowId: String,
    preferredPhaseId: String,
    reason: String,
    dbPathOverride: String? = null,
  ): Boolean
}
