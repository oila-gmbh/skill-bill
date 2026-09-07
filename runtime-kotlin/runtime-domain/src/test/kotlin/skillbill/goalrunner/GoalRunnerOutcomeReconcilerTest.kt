package skillbill.goalrunner

import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoalRunnerOutcomeReconcilerTest {
  private val launchFacts = GoalRunnerLaunchFacts()

  @Test
  fun `a reconciled child row routes to resumable instead of the terminal no-terminal-store block`() {
    // AC-002 / task-5: a crash-reconciled child row stops with RECONCILED_RESUMABLE, not the terminal
    // NO_TERMINAL_STORE_OUTCOME, so the goal parent keeps the subtask resumable at its recorded step.
    val outcome = GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.RECONCILABLE,
      workflowId = "wf-reconciled",
      lastResumableStep = "implement",
      suppressPr = true,
    )

    val reconciled = GoalRunnerOutcomeReconciler.reconcile(subtaskId = 3, launchFacts, outcome)

    val stop = assertIs<GoalRunnerReconciledOutcome.Stop>(reconciled)
    assertEquals(GoalRunnerStopReason.RECONCILED_RESUMABLE, stop.reason)
    assertEquals("implement", stop.lastResumableStep)
    assertEquals("wf-reconciled", stop.workflowId)
  }

  @Test
  fun `a no-terminal-store outcome still blocks with the existing reason`() {
    // task-5: the new reconcilable path does not weaken the existing terminal block for rows that are
    // genuinely without a store outcome.
    val outcome = GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME,
      workflowId = "wf-stranded",
      suppressPr = true,
    )

    val reconciled = GoalRunnerOutcomeReconciler.reconcile(subtaskId = 4, launchFacts, outcome)

    val stop = assertIs<GoalRunnerReconciledOutcome.Stop>(reconciled)
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stop.reason)
  }

  @Test
  fun `a reconcilable row that did not suppress per-subtask PRs blocks rather than going resumable`() {
    val outcome = GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.RECONCILABLE,
      workflowId = "wf-reconciled",
      lastResumableStep = "plan",
      suppressPr = false,
    )

    val reconciled = GoalRunnerOutcomeReconciler.reconcile(subtaskId = 5, launchFacts, outcome)

    val stop = assertIs<GoalRunnerReconciledOutcome.Stop>(reconciled)
    assertEquals(GoalRunnerStopReason.BLOCKED, stop.reason)
  }
}
