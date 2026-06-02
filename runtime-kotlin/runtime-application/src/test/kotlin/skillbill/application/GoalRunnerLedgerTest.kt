package skillbill.application

import skillbill.application.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionSubtask
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-64 Subtask 4 (AC4, AC5): the attempt/event ledger and best-effort
 * session accounting must explain every case — first start, resume/retry,
 * terminal done check, timeout/interruption, policy-blocked, and the final
 * reconciled result — WITHOUT provider JSONL scraping.
 *
 * Each case is driven end-to-end through [GoalRunner.run] using the recording
 * fixtures, exercising the live emit -> store -> read seam in ONE run (the
 * dead-seam guard): a regression that breaks the wiring between the ledger
 * action map (GoalRunner) and the durable record (RecordingOutcomeStore) fails
 * here, not just in an isolated model test.
 */
class GoalRunnerLedgerTest {
  @Test
  fun `first start records child activation terminal done check and final reconciled outcome`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      // Provider-neutral child session descriptors present -> accounting available.
      launchFacts().copy(childSessionPath = "/work/child-1", childSessionId = "claude:SKILL-56:subtask-1")
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    assertIs<GoalRunnerRunReport.Completed>(runner.run(ledgerRunRequest()))

    val actions = ledgerActions(outcomes)
    assertContains(actions, "child_activation")
    assertContains(actions, "terminal_done_check")
    assertContains(actions, "final_reconciled_outcome")
    // The ledger explains the terminal result without provider JSONL scraping:
    // the explanatory fields are carried on the entries themselves.
    val terminalDoneCheck = outcomes.attemptLedgerRecords
      .first { it.entry.action.wireValue == "terminal_done_check" }.entry
    assertContains(requireNotNull(terminalDoneCheck.finalReconciledResult), "complete")
    val finalReconciled = outcomes.attemptLedgerRecords
      .first { it.entry.action.wireValue == "final_reconciled_outcome" }.entry
    assertContains(requireNotNull(finalReconciled.finalReconciledResult), "goal_finalize")

    // Monotonic per-recorder sequence space (distinct from goal_event/goal_progress).
    assertEquals(
      outcomes.attemptLedgerRecords.map { it.entry.sequenceNumber }.sorted(),
      outcomes.attemptLedgerRecords.map { it.entry.sequenceNumber },
    )

    // AC4: best-effort accounting persisted and available when provider session
    // data exists.
    val accounting = outcomes.sessionAccountingRecords.single().accounting
    assertTrue(accounting.available)
    assertEquals("/work/child-1", accounting.childSessionPath)
    assertEquals("claude:SKILL-56:subtask-1", accounting.childSessionId)
  }

  @Test
  fun `resume selection records a resume ledger action for a previously blocked subtask`() {
    val initial = manifest(subtaskCount = 1).copy(
      status = "blocked",
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "blocked"),
      subtasks = listOf(
        DecompositionSubtask(
          id = 1,
          name = "Subtask 1",
          specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
          status = "blocked",
          workflowId = "wfl-1",
          blockedReason = "validation failed",
          lastResumableStep = "validate",
        ),
      ),
    )
    val store = InMemoryGoalManifestStore(manifest = initial)
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    assertIs<GoalRunnerRunReport.Completed>(runner.run(ledgerRunRequest()))

    val actions = ledgerActions(outcomes)
    assertContains(actions, "resume")
    assertFalse(actions.contains("child_activation"), "a resume must not be recorded as a child activation: $actions")
  }

  @Test
  fun `no terminal store outcome records a retry ledger action`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    // Launcher never stores a terminal outcome -> NO_TERMINAL_STORE_OUTCOME -> retry.
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME, stopped.stop.reason)

    val retry = outcomes.attemptLedgerRecords.firstOrNull { it.entry.action.wireValue == "retry" }
    assertTrue(retry != null, "expected a retry ledger entry: ${ledgerActions(outcomes)}")
    // The ledger explains the stop without provider JSONL scraping.
    assertEquals("no_terminal_store_outcome", retry.entry.stopReason)
  }

  @Test
  fun `timeout stop reason records a timeout ledger action that explains the stop`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      launchFacts(timedOut = true)
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.TIMEOUT, stopped.stop.reason)

    val timeout = outcomes.attemptLedgerRecords.first { it.entry.action.wireValue == "timeout" }.entry
    assertEquals("timeout", timeout.stopReason)
    // AC4: accounting is recorded as unavailable (no provider data) WITHOUT
    // failing the run.
    assertTrue(outcomes.sessionAccountingRecords.isNotEmpty())
    val unavailable = outcomes.sessionAccountingRecords.last().accounting
    assertFalse(unavailable.available)
    assertContains(requireNotNull(unavailable.unavailableReason), "not available")
  }

  @Test
  fun `interruption stop reason records an interruption ledger action`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      launchFacts(interrupted = true)
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.INTERRUPTED, stopped.stop.reason)

    val interruption = outcomes.attemptLedgerRecords.first { it.entry.action.wireValue == "interruption" }.entry
    assertEquals("interrupted", interruption.stopReason)
  }

  @Test
  fun `policy blocked records a policy block ledger action without scraping provider logs`() {
    // Feature branch resolves to a protected branch -> policy block before any
    // child launch, anchored to the parent decomposed workflow id.
    val store = InMemoryGoalManifestStore(
      manifest = manifest(subtaskCount = 1).copy(featureBranch = "main"),
    )
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { launchFacts() }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.POLICY_BLOCKED, stopped.stop.reason)

    val policyBlock = outcomes.attemptLedgerRecords.first { it.entry.action.wireValue == "policy_block" }.entry
    assertEquals("policy_blocked", policyBlock.stopReason)
    assertContains(requireNotNull(policyBlock.blockedReason), "protected branch")
  }

  @Test
  fun `failed subtask records a final reconciled result with the failure explanation`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 1))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = GoalRunnerStoredOutcome(
        status = GoalRunnerTerminalStatus.FAILED,
        workflowId = "wfl-$subtaskId",
        blockedReason = "review failed",
        lastResumableStep = "review",
        suppressPr = true,
      )
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val stopped = assertIs<GoalRunnerRunReport.Stopped>(runner.run(ledgerRunRequest()))
    assertEquals(GoalRunnerStopReason.FAILED, stopped.stop.reason)

    // The terminal failure is explained on the ledger entry (final_reconciled_result
    // + blocked_reason), so no provider JSONL scraping is required to answer why
    // the subtask stopped.
    val failedEntry = outcomes.attemptLedgerRecords.last { it.entry.stopReason == "failed" }.entry
    assertEquals("failed", failedEntry.finalReconciledResult)
    assertContains(requireNotNull(failedEntry.blockedReason), "review failed")
  }

  private fun ledgerActions(outcomes: RecordingOutcomeStore): List<String> =
    outcomes.attemptLedgerRecords.map { it.entry.action.wireValue }

  private fun ledgerRunRequest(): GoalRunnerRunRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = Path.of("/tmp/skillbill-goal-runner"),
    invokedAgentId = "claude",
    dbPathOverride = "/tmp/skillbill-goal-runner/metrics.db",
  )
}
