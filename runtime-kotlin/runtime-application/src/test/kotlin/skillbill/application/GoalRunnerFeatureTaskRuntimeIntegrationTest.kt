package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalRunnerFeatureTaskRuntimeIntegrationTest {
  @Test
  fun `goal runner stops when a completed runtime report carries a blocked child outcome`() {
    val parity = goalChildParityRun(
      launcher = defaultPhaseAwareLauncher(),
      config = GoalChildParityConfig(
        gitOperations = RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal"),
        ensureCommitSha = false,
      ),
    )

    val child = assertIs<FeatureTaskRuntimeRunReport.Completed>(parity.childReports.single())
    assertEquals("blocked", child.subtaskOutcome?.status)
    val stopped = assertIs<GoalRunnerRunReport.Stopped>(parity.report)
    assertContains(stopped.stop.blockedReason, "no commit SHA")
    assertEquals(WORKFLOW_ID, stopped.stop.workflowId)
    assertEquals("commit_push", stopped.stop.lastResumableStep)
  }

  @Test
  fun `authoritative terminal fields are preserved for complete blocked and failed child reports`() {
    val completeRun = goalRunForChildReport(
      completedChildReport(status = "complete", commitSha = "sha-complete", reason = null, step = "commit_push"),
    )
    assertIs<GoalRunnerRunReport.Completed>(completeRun.first)
    val complete = completeRun.second
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, complete.status)
    assertEquals("sha-complete", complete.commitSha)
    assertEquals(WORKFLOW_ID, complete.workflowId)
    assertEquals(null, complete.blockedReason)
    assertEquals("commit_push", complete.lastResumableStep)

    val blockedRun = goalRunForChildReport(
      completedChildReport(
        status = "blocked",
        commitSha = "sha-blocked",
        reason = "durable reason",
        step = "implement",
      ),
    )
    assertIs<GoalRunnerRunReport.Stopped>(blockedRun.first)
    val blocked = blockedRun.second
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, blocked.status)
    assertEquals("sha-blocked", blocked.commitSha)
    assertEquals(WORKFLOW_ID, blocked.workflowId)
    assertEquals("durable reason", blocked.blockedReason)
    assertEquals("implement", blocked.lastResumableStep)

    val failedRun = goalRunForChildReport(
      FeatureTaskRuntimeRunReport.Decomposed(
        issueKey = "SKILL-56",
        workflowId = WORKFLOW_ID,
        featureSize = "MEDIUM",
        reason = "terminal failure",
        completedPhaseIds = listOf("preplan"),
        parentSpecPath = "spec.md",
        decompositionManifestPath = "manifest.yaml",
        subtaskSpecPaths = listOf("subtask.md"),
        resolvedBranch = "feat/SKILL-56-goal",
      ),
    )
    assertIs<GoalRunnerRunReport.Stopped>(failedRun.first)
    val failed = failedRun.second
    assertEquals(GoalRunnerTerminalStatus.FAILED, failed.status)
    assertEquals(WORKFLOW_ID, failed.workflowId)
    assertEquals(null, failed.commitSha)
    assertEquals("terminal failure", failed.blockedReason)
    assertEquals("plan", failed.lastResumableStep)
  }

  @Test
  fun `goal review policy and exact baseline reach the runtime child review prompt`() {
    val workflowId = WORKFLOW_ID
    val outcomes = RecordingOutcomeStore().apply { seedReviewState(workflowId) }
    val phaseLauncher = defaultPhaseAwareLauncher()
    val gitOperations = RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal")
      .apply { headCommitShaValue = "goal-child-commit" }
    val runtime = runnerHarness(
      launcher = phaseLauncher,
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = gitOperations,
        ),
      ),
    )
    val manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).withWorkflowId(1, workflowId))
    val goalRunner = GoalRunner(
      manifestStore = manifestStore,
      subtaskLauncher = RuntimeChildLauncher(runtime.runner, runtime.request(), outcomes),
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
    )

    val report = goalRunner.run(
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = runtime.request().repoRoot,
        invokedAgentId = INVOKED_AGENT,
        codeReviewMode = CodeReviewExecutionMode.DELEGATED,
        parallelReviewAgent = "claude",
      ),
    )

    assertIs<GoalRunnerRunReport.Completed>(report)
    val reviewPrompts = phaseLauncher.requests
      .mapNotNull { it.skillRunRequest.promptOverride }
      .filter { it.contains("Phase: review") }
    assertEquals(1, reviewPrompts.size)
    assertContains(reviewPrompts.single(), "bill-code-review mode:delegated")
    assertContains(reviewPrompts.single(), "Combine it with `parallel:claude`")
    assertContains(reviewPrompts.single(), "durable base `${"0".repeat(40)}`")
    assertContains(reviewPrompts.single(), "committed, staged, unstaged, and owned untracked changes")
    assertContains(reviewPrompts.single(), "Do not use `origin/main...HEAD`")
    assertEquals(CodeReviewExecutionMode.DELEGATED, runtime.runInvariantsStore.resolve(workflowId)?.codeReviewMode)
    assertTrue(outcomes.acknowledgedReviewPasses.all { (_, passNumber) -> passNumber <= 2 })
  }

  @Test
  fun `standalone and goal child preserve delegated parallel review composition`() {
    val parity = standaloneAndGoalChildParity(
      launcher = ::defaultPhaseAwareLauncher,
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
      parallelReviewAgent = "claude",
    )

    assertIs<GoalRunnerRunReport.Completed>(parity.report)
    val reviews = parity.runtime.goalChildObservation(
      parity.childReports.last(),
      parity.authoritativeOutcome(),
    ).reviewComposition
    assertEquals(1, reviews.size)
    assertContains(reviews.single(), "bill-code-review mode:delegated|parallel:claude")
    assertContains(reviews.single(), "durable base `${"0".repeat(40)}`")
    assertContains(reviews.single(), "committed, staged, unstaged")
  }

  @Test
  fun `goal child audit gap reuses initial planning context and resumes at implement`() {
    val workflowId = WORKFLOW_ID
    val outcomes = RecordingOutcomeStore().apply { seedReviewState(workflowId) }
    val phaseLauncher = auditGapLauncher(convergeOnAudit = 2)
    val runtime = runnerHarness(
      launcher = phaseLauncher,
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(
          gitOperations = RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-126-goal"),
        ),
      ),
    )
    val goalRunner = GoalRunner(
      manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).withWorkflowId(1, workflowId)),
      subtaskLauncher = RuntimeChildLauncher(runtime.runner, runtime.request(), outcomes),
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
    )

    val report = goalRunner.run(
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = runtime.request().repoRoot,
        invokedAgentId = INVOKED_AGENT,
      ),
    )

    assertIs<GoalRunnerRunReport.Completed>(report)
    val launched = phaseLauncher.requests.map {
      phaseIdFromPrompt(requireNotNull(it.skillRunRequest.promptOverride))
    }
    assertEquals(1, launched.count { it == "preplan" })
    assertEquals(1, launched.count { it == "plan" })
    assertEquals(2, launched.count { it == "implement" })
    val remediationPrompt = phaseLauncher.requests
      .map { requireNotNull(it.skillRunRequest.promptOverride) }
      .filter { it.contains("Phase: implement") }
      .last()
    assertContains(remediationPrompt, "### from: preplan")
    assertContains(remediationPrompt, "### from: plan")
    assertContains(remediationPrompt, "AC-2 acceptance criterion is not yet implemented")
    val planningRecords = runtime.recorder.loadPhaseRecords(workflowId).orEmpty()
    assertEquals(1, planningRecords.getValue("preplan").attemptCount)
    assertEquals(1, planningRecords.getValue("plan").attemptCount)
    assertEquals(null, planningRecords.getValue("preplan").loopId)
    assertEquals(null, planningRecords.getValue("plan").loopId)
    val reviewCompletions = runtime.recorder.loadPhaseLedger(workflowId).orEmpty()
      .filter { it.action == skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction.COMPLETE }
      .filter { it.phaseId == "review" }
    // The goal child resolves the same audit-first graph as a standalone run: the audit_gap loop
    // reopens only [implement, audit], so review completes exactly once, outside the loop.
    assertEquals(1, reviewCompletions.size)
    assertEquals(0, reviewCompletions.count { it.loopId == "audit_gap" })
  }

  @Test
  fun `goal child rejects a partial remediation with the same identifier-equality reason`() {
    fun launcher(): RuntimeRecordingLauncher {
      var implementLaunches = 0
      return RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(auditTwoItemGoalGapOutput())
          "implement" -> {
            implementLaunches += 1
            if (implementLaunches == 1) {
              facts(validJsonOutput(phaseId))
            } else {
              facts(goalRemediationOutput(reportedItemIds = listOf("ac-002-gap-1-item-1")))
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      }
    }
    val parity = standaloneAndGoalChildParity(launcher = ::launcher)

    assertIs<GoalRunnerRunReport.Stopped>(parity.report)
    val blocked = assertNotNull(parity.blockedChildReason())
    assertContains(blocked, "exact repair_item_result identifier equality")
    assertContains(blocked, "ac-002-gap-1-item-2")
    val repairState = requireNotNull(parity.runtime.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(listOf("ac-002-gap-1"), repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
  }

  @Test
  fun `standalone and goal child complete the same exhaustive multi-item repair`() {
    fun launcher(): RuntimeRecordingLauncher {
      var audits = 0
      var initialImplementComplete = false
      return RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        when (val phaseId = phaseIdFromPrompt(prompt)) {
          "audit" -> {
            audits += 1
            facts(if (audits == 1) auditTwoItemGoalGapOutput() else auditSatisfiedOutput())
          }
          "implement" -> {
            if (!initialImplementComplete) {
              initialImplementComplete = true
              facts(validJsonOutput(phaseId))
            } else {
              facts(
                goalRemediationOutput(
                  listOf("ac-002-gap-1-item-1", "ac-002-gap-1-item-2"),
                ),
              )
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      }
    }

    val parity = standaloneAndGoalChildParity(launcher = ::launcher)

    assertIs<GoalRunnerRunReport.Completed>(parity.report)
    val observation = parity.runtime.goalChildObservation(
      parity.childReports.last(),
      parity.authoritativeOutcome(),
    )
    assertEquals(
      listOf("ac-002-gap-1-item-1", "ac-002-gap-1-item-2"),
      observation.repairResults.map { result ->
        (result as skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult).repairItemId
      },
    )
    assertEquals(observation.terminalReport, observation.authoritativeOutcome)
    assertTrue(observation.unresolvedLedger.toString().contains("unresolvedGaps=[]"))
  }

  @Test
  fun `goal child rejects a remediation deferring carried work to a later phase`() {
    fun launcher(): RuntimeRecordingLauncher {
      var implementLaunches = 0
      return RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> facts(auditGapsOutput())
          "implement" -> {
            implementLaunches += 1
            facts(
              if (implementLaunches == 1) {
                validJsonOutput(phaseId)
              } else {
                validJsonOutput(phaseId).replace(
                  "\"summary\": \"Phase produced a validated output.\"",
                  "\"summary\": \"Deferred the remaining repair to the validation phase.\"",
                )
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      }
    }
    val parity = standaloneAndGoalChildParity(launcher = ::launcher)

    assertIs<GoalRunnerRunReport.Stopped>(parity.report)
    assertContains(assertNotNull(parity.blockedChildReason()), "later phase")
  }

  @Test
  fun `goal child blocks on a non-progressing equivalent gap set`() {
    val parity = standaloneAndGoalChildParity(
      launcher = { auditGapLauncher(convergeOnAudit = 3) },
      gitOperations = {
        RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal")
          .apply { repositoryFingerprintValue = "unchanged" }
      },
    )

    assertIs<GoalRunnerRunReport.Stopped>(parity.report)
    val blocked = assertNotNull(parity.blockedChildReason())
    assertContains(blocked, "Audit repair made no progress")
    assertContains(blocked, "repository fingerprint is unchanged")
    val repairState = requireNotNull(parity.runtime.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(
      listOf("ac-002-gap-1"),
      repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId },
      "the carried gap keeps its identity across the goal child's audit iterations",
    )
    assertTrue(parity.runtime.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"] == null)
  }

  @Test
  fun `standalone and goal child reject a new gap against a durably closed criterion`() {
    fun launcher(): RuntimeRecordingLauncher {
      var audits = 0
      var implements = 0
      return RuntimeRecordingLauncher { request ->
        when (val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))) {
          "audit" -> {
            audits += 1
            facts(if (audits == 1) auditGapsOutput() else auditTwoGapsOutput())
          }
          "implement" -> {
            implements += 1
            if (implements == 3) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      }
    }

    val parity = standaloneAndGoalChildParity(
      launcher = ::launcher,
      acceptanceCriteria = (1..3).map { "AC-$it" },
    )

    assertIs<GoalRunnerRunReport.Stopped>(parity.report)
    assertContains(requireNotNull(parity.blockedChildReason()), "durably closed acceptance criteria [AC-003]")
    val state = requireNotNull(parity.runtime.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(0, state.progress.recurringGapCount)
    assertEquals(1, state.progress.newGapCount)
    assertEquals(listOf("ac-002-gap-1"), state.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
    assertEquals(listOf("AC-001", "AC-003"), state.satisfiedCriterionRefs)
  }

  @Test
  fun `standalone and goal child resume the same durable scenario after a mid-item crash`() {
    val standaloneCompletion = MidItemParityScenario("standalone-mid-item").completeStandalone()
    val goalCompletion = MidItemParityScenario("goal-mid-item").completeGoalChild()
    val standaloneObservation = standaloneCompletion.runtime.goalChildObservation(standaloneCompletion.report)
    val goalObservation = goalCompletion.runtime.goalChildObservation(
      goalCompletion.report,
      requireNotNull(goalCompletion.authoritativeOutcome),
    )
    assertEquals(
      standaloneObservation.copy(reviewComposition = goalObservation.reviewComposition),
      goalObservation,
    )
    assertReviewCompositionParity(standaloneObservation, goalObservation)
    assertTrue(standaloneCompletion.runtime.hasPhase("validate"))
    assertTrue(goalCompletion.runtime.hasPhase("validate"))
  }

  @Test
  fun `goal child parses every canonical wrapper form into the same run`() {
    val observed = GOAL_CHILD_WRAPPER_FORMS.mapValues { (_, wrap) ->
      val standalone = runnerHarness(
        launcher = wrappedAuditGapLauncher(convergeOnAudit = 2, wrap = wrap),
        validator = CanonicalWrapperTestValidator,
      )
      assertEquals(null, standalone.request().goalContinuation)
      val standaloneReport =
        assertIs<FeatureTaskRuntimeRunReport.Completed>(standalone.runner.run(standalone.request()))
      val standaloneObservation = standalone.goalChildObservation(standaloneReport)
      val parity = goalChildParityRun(
        launcher = wrappedAuditGapLauncher(convergeOnAudit = 2, wrap = wrap),
        config = GoalChildParityConfig(validator = CanonicalWrapperTestValidator),
      )
      assertIs<GoalRunnerRunReport.Completed>(parity.report)
      parity.runtime.goalChildObservation(parity.childReports.last(), parity.authoritativeOutcome()).also {
        assertEquals(
          standaloneObservation.copy(reviewComposition = it.reviewComposition),
          it,
          "standalone and goal-child observations must match",
        )
        assertReviewCompositionParity(standaloneObservation, it)
      }
    }

    val bare = observed.getValue("bare")
    observed.forEach { (form, actual) ->
      assertEquals(bare, actual, "goal-child wrapper form '$form' must produce the identical normalized run")
    }
    assertEquals(listOf(1), bare.auditGapEdgeIterations)
  }
}

private data class GoalChildObservation(
  val phaseOrder: List<String>,
  val persistedOutputs: Map<String, String>,
  val auditGapEdgeIterations: List<Int>,
  val acceptedPlans: List<Any>,
  val repairResults: List<Any>,
  val unresolvedLedger: Any?,
  val dispositions: List<Any>,
  val remediationHandoffs: List<String>,
  val reviewComposition: List<String>,
  val terminalReport: TerminalObservation,
  val authoritativeOutcome: TerminalObservation,
)

private data class TerminalObservation(
  val status: String,
  val reason: String?,
  val commitSha: String?,
  val workflowId: String,
  val lastResumableStep: String,
)

private data class MidItemCompletion(
  val runtime: RunnerHarness,
  val report: FeatureTaskRuntimeRunReport.Completed,
  val authoritativeOutcome: GoalRunnerStoredOutcome? = null,
)

private class MidItemParityScenario(tempDirectoryPrefix: String) {
  private val repository = MidItemScenarioRepository(Files.createTempDirectory(tempDirectoryPrefix))
  private val gitOperations = RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal")
    .apply { headCommitShaValue = "goal-child-commit" }
  private val outcomes = RecordingOutcomeStore().apply { seedReviewState(WORKFLOW_ID) }

  fun completeStandalone(): MidItemCompletion {
    val beforeCrash = runtime(crashAfterLanding = true)
    assertEquals(null, beforeCrash.request().goalContinuation)
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(beforeCrash.runner.run(beforeCrash.request()))
    assertEquals(setOf("item-1"), repository.effects())

    repository.removeEffect("item-1")
    val missingEffect = runtime(crashAfterLanding = false, landingEnabled = false)
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(missingEffect.runner.run(missingEffect.request()))
    assertTrue(!missingEffect.hasPhase("validate"))

    repository.landEffect("item-1")
    val completedRuntime = runtime(crashAfterLanding = false)
    val report = assertIs<FeatureTaskRuntimeRunReport.Completed>(
      completedRuntime.runner.run(completedRuntime.request()),
    )
    return MidItemCompletion(completedRuntime, report)
  }

  fun completeGoalChild(): MidItemCompletion {
    val crashedReport = runGoal(runtime(crashAfterLanding = true)).second
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(crashedReport)
    assertEquals(setOf("item-1"), repository.effects())

    repository.removeEffect("item-1")
    val missingEffect = runtime(crashAfterLanding = false, landingEnabled = false)
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(runGoal(missingEffect).second)
    assertTrue(!missingEffect.hasPhase("validate"))

    repository.landEffect("item-1")
    val (completedRuntime, completedReport) = runGoal(runtime(crashAfterLanding = false))
    return MidItemCompletion(
      completedRuntime,
      assertIs<FeatureTaskRuntimeRunReport.Completed>(completedReport),
      requireNotNull(outcomes.terminalOutcome(WORKFLOW_ID, "SKILL-56", 1, null)),
    )
  }

  private fun runtime(crashAfterLanding: Boolean, landingEnabled: Boolean = true): RunnerHarness = runnerHarness(
    launcher = launcher(crashAfterLanding, landingEnabled),
    repository = repository.workflow,
    runtimeConfig = RuntimeHarnessConfig(
      branchSetup = BranchSetupTestConfig(gitOperations = gitOperations),
    ),
  )

  private fun runGoal(runtime: RunnerHarness): Pair<RunnerHarness, FeatureTaskRuntimeRunReport> {
    val childLauncher = RuntimeChildLauncher(runtime.runner, runtime.request(), outcomes)
    GoalRunner(
      manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).withWorkflowId(1, WORKFLOW_ID)),
      subtaskLauncher = childLauncher,
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
    ).run(GoalRunnerRunRequest("SKILL-56", runtime.request().repoRoot, INVOKED_AGENT))
    return runtime to childLauncher.reports.last()
  }

  private fun launcher(crashAfterLanding: Boolean, landingEnabled: Boolean): RuntimeRecordingLauncher =
    RuntimeRecordingLauncher { request ->
      val prompt = requireNotNull(request.skillRunRequest.promptOverride)
      when (val phaseId = phaseIdFromPrompt(prompt)) {
        "audit" -> facts(if (repository.hasEffect("item-1")) auditSatisfiedOutput() else auditGapsOutput())
        "implement" -> implementFacts(prompt, phaseId, crashAfterLanding, landingEnabled)
        else -> facts(validJsonOutput(phaseId))
      }
    }

  private fun implementFacts(prompt: String, phaseId: String, crashAfterLanding: Boolean, landingEnabled: Boolean) =
    when {
      !prompt.contains("audit_repair_plan") -> facts(validJsonOutput(phaseId))
      !repository.hasEffect("item-1") && !landingEnabled -> spawnFailedFacts()
      !repository.hasEffect("item-1") -> {
        repository.landEffect("item-1")
        if (crashAfterLanding) spawnFailedFacts() else facts(validJsonOutput(phaseId))
      }
      crashAfterLanding -> {
        assertTrue(runCatching { repository.landEffect("item-1") }.isFailure)
        spawnFailedFacts()
      }
      else -> facts(validJsonOutput(phaseId))
    }
}

private fun RunnerHarness.hasPhase(phaseId: String): Boolean =
  recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()[phaseId] != null

private class MidItemScenarioRepository(private val repositoryRoot: Path) {
  val workflow = InMemoryRuntimeWorkflowRepository()

  fun hasEffect(effect: String): Boolean = Files.exists(effectPath(effect))

  fun landEffect(effect: String) {
    Files.createFile(effectPath(effect))
  }

  fun removeEffect(effect: String) {
    check(Files.deleteIfExists(effectPath(effect))) { "missing repository effect: $effect" }
  }

  fun effects(): Set<String> = Files.list(repositoryRoot).use { paths ->
    paths.map { it.fileName.toString() }.toList().toSet()
  }

  private fun effectPath(effect: String): Path = repositoryRoot.resolve(effect)
}

private fun RunnerHarness.goalChildObservation(
  report: FeatureTaskRuntimeRunReport,
  storedOutcome: GoalRunnerStoredOutcome? = null,
): GoalChildObservation {
  val terminalReport = report.terminalObservation(
    recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["commit_push"]?.outputArtifact
      ?.let { Regex("\\\"commit_sha\\\":\\\"([^\\\"]+)\\\"").find(it)?.groupValues?.get(1) }
      ?: gitOperations.headCommitShaValue,
  )
  return GoalChildObservation(
    phaseOrder = launchedPromptPhaseOrder().filterNot { it == "pr" },
    persistedOutputs = recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
      .filterKeys { it != "pr" }
      .mapNotNull { (phaseId, record) -> record.outputArtifact?.let { phaseId to it } }.toMap(),
    auditGapEdgeIterations = recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
      .mapNotNull { it.edgeIteration },
    acceptedPlans = recorder.loadAuditRepairState(WORKFLOW_ID)?.acceptedPlans.orEmpty(),
    repairResults = recorder.loadAuditRepairState(WORKFLOW_ID)?.repairItemResults.orEmpty(),
    unresolvedLedger = recorder.loadAuditRepairState(WORKFLOW_ID)?.unresolvedGapLedger,
    dispositions = recorder.loadAuditRepairState(WORKFLOW_ID)?.priorGapDispositions.orEmpty(),
    remediationHandoffs = launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }
      .filter { it.contains("Phase: implement") && it.contains("audit_repair_plan") }
      .map { prompt ->
        prompt.substringAfter("audit_repair_plan:\n").substringBefore("audit_remediation_execution_rules:").trim()
      },
    reviewComposition = launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }
      .filter { it.contains("Phase: review") }
      .map(::reviewComposition),
    terminalReport = terminalReport,
    authoritativeOutcome = storedOutcome?.terminalObservation() ?: terminalReport,
  )
}

private fun reviewComposition(prompt: String): String = listOf(
  Regex("bill-code-review mode:[^`\\s]+").find(prompt)?.value.orEmpty(),
  Regex("parallel:[^`\\s]+").find(prompt)?.value.orEmpty(),
  Regex("durable base `[^`]+`").find(prompt)?.value.orEmpty(),
  prompt.lineSequence().firstOrNull { it.contains("committed, staged, unstaged") }.orEmpty().trim(),
).joinToString("|")

private fun GoalRunnerStoredOutcome.terminalObservation(): TerminalObservation = TerminalObservation(
  status = status.name.lowercase(),
  reason = blockedReason,
  commitSha = commitSha,
  workflowId = workflowId,
  lastResumableStep = requireNotNull(lastResumableStep),
)

private fun FeatureTaskRuntimeRunReport.terminalObservation(fallbackCommitSha: String?): TerminalObservation {
  val outcome = when (this) {
    is FeatureTaskRuntimeRunReport.Completed -> subtaskOutcome
    is FeatureTaskRuntimeRunReport.Blocked -> subtaskOutcome
    is FeatureTaskRuntimeRunReport.Decomposed -> null
  }
  return if (outcome != null) {
    TerminalObservation(
      outcome.status,
      outcome.blockedReason,
      outcome.commitSha,
      outcome.workflowId,
      outcome.lastResumableStep,
    )
  } else {
    when (this) {
      is FeatureTaskRuntimeRunReport.Completed ->
        TerminalObservation("complete", null, fallbackCommitSha, workflowId, "commit_push")
      is FeatureTaskRuntimeRunReport.Blocked ->
        TerminalObservation("blocked", blockedReason, null, workflowId, lastIncompletePhase)
      is FeatureTaskRuntimeRunReport.Decomposed ->
        TerminalObservation("failed", reason, null, workflowId, "plan")
    }
  }
}

private val GOAL_CHILD_WRAPPER_FORMS: Map<String, (String) -> String> = mapOf(
  "bare" to { text -> text },
  "bare_trailing_prose" to { text -> "$text\nThe structured result above is authoritative." },
  "fenced" to { text -> "```json\n$text\n```" },
  "markdown_prefixed" to { text ->
    "## Phase result\n\nEvidence precedes the envelope.\n\n```json\n$text\n```\n\nCommentary follows."
  },
)

private fun wrappedAuditGapLauncher(convergeOnAudit: Int, wrap: (String) -> String): RuntimeRecordingLauncher {
  var auditLaunches = 0
  return RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    facts(
      wrap(
        when {
          phaseId != "audit" -> validJsonOutput(phaseId)
          else -> {
            auditLaunches += 1
            if (auditLaunches < convergeOnAudit) auditGapsOutput() else auditSatisfiedOutput()
          }
        },
      ),
    )
  }
}

private class GoalChildParityRun(
  val report: GoalRunnerRunReport,
  val runtime: RunnerHarness,
  val childReports: List<FeatureTaskRuntimeRunReport>,
  val continuationRequestCount: Int,
  private val outcomes: RecordingOutcomeStore,
  val resume: () -> GoalRunnerRunReport,
) {
  fun authoritativeOutcome(): GoalRunnerStoredOutcome =
    requireNotNull(outcomes.terminalOutcome(WORKFLOW_ID, "SKILL-56", 1, null))
}

private fun GoalChildParityRun.blockedChildReason(): String? =
  (report as? GoalRunnerRunReport.Stopped)?.stop?.blockedReason

private fun standaloneAndGoalChildParity(
  launcher: () -> RuntimeRecordingLauncher,
  gitOperations: () -> RecordingWorkflowGitOperations = {
    RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal")
  },
  codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DELEGATED,
  parallelReviewAgent: String? = null,
  acceptanceCriteria: List<String> = listOf("AC-1", "AC-2"),
): GoalChildParityRun {
  val standaloneGit = gitOperations().apply {
    if (headCommitShaValue.isBlank()) headCommitShaValue = "goal-child-commit"
  }
  val standalone = runnerHarness(
    launcher = launcher(),
    runtimeConfig = RuntimeHarnessConfig(
      branchSetup = BranchSetupTestConfig(gitOperations = standaloneGit),
      acceptanceCriteria = acceptanceCriteria,
    ),
  )
  val standaloneRequest = standalone.request().copy(
    requestedCodeReviewMode = codeReviewMode,
    parallelReviewAgent = parallelReviewAgent,
  )
  assertEquals(null, standaloneRequest.goalContinuation)
  val standaloneReport = standalone.runner.run(standaloneRequest)
  val goalChild = goalChildParityRun(
    launcher = launcher(),
    config = GoalChildParityConfig(
      gitOperations = gitOperations(),
      codeReviewMode = codeReviewMode,
      parallelReviewAgent = parallelReviewAgent,
      acceptanceCriteria = acceptanceCriteria,
    ),
  )
  assertEquals(goalChild.childReports.size, goalChild.continuationRequestCount)
  assertEquals(
    standalone.goalChildObservation(standaloneReport).copy(
      reviewComposition = goalChild.runtime
        .goalChildObservation(goalChild.childReports.last(), goalChild.authoritativeOutcome()).reviewComposition,
    ),
    goalChild.runtime.goalChildObservation(goalChild.childReports.last(), goalChild.authoritativeOutcome()),
    "standalone and goal-child durable scenario observations must match",
  )
  assertReviewCompositionParity(
    standalone.goalChildObservation(standaloneReport),
    goalChild.runtime.goalChildObservation(goalChild.childReports.last(), goalChild.authoritativeOutcome()),
  )
  if (standaloneReport is FeatureTaskRuntimeRunReport.Blocked) {
    val goalReason = assertNotNull(goalChild.blockedChildReason())
    assertContains(goalReason, standaloneReport.blockedReason)
    assertEquals(
      standaloneReport.lastIncompletePhase,
      assertIs<GoalRunnerRunReport.Stopped>(goalChild.report).stop.lastResumableStep,
    )
  }
  return goalChild
}

private fun assertReviewCompositionParity(standalone: GoalChildObservation, goalChild: GoalChildObservation) {
  assertEquals(standalone.reviewComposition.size, goalChild.reviewComposition.size)
  assertEquals(
    standalone.reviewComposition.map { it.split('|').take(2) },
    goalChild.reviewComposition.map { it.split('|').take(2) },
  )
  assertTrue(standalone.reviewComposition.none { it.contains("durable base") })
  assertTrue(goalChild.reviewComposition.all { it.contains("durable base `${"0".repeat(40)}`") })
  assertTrue(goalChild.reviewComposition.all { it.contains("committed, staged, unstaged") })
}

private fun completedChildReport(
  status: String,
  commitSha: String?,
  reason: String?,
  step: String,
): FeatureTaskRuntimeRunReport.Completed = FeatureTaskRuntimeRunReport.Completed(
  issueKey = "SKILL-56",
  workflowId = WORKFLOW_ID,
  featureSize = "MEDIUM",
  completedPhaseIds = listOf("preplan", "plan", "implement"),
  resolvedBranch = "feat/SKILL-56-goal",
  subtaskOutcome = FeatureTaskRuntimeSubtaskOutcome(
    issueKey = "SKILL-56",
    subtaskId = 1,
    status = status,
    commitSha = commitSha,
    workflowId = WORKFLOW_ID,
    blockedReason = reason,
    lastResumableStep = step,
  ),
)

private fun goalRunForChildReport(
  childReport: FeatureTaskRuntimeRunReport,
): Pair<GoalRunnerRunReport, GoalRunnerStoredOutcome> {
  val outcomes = RecordingOutcomeStore().apply { seedReviewState(WORKFLOW_ID) }
  val launcher = object : GoalRunnerSubtaskLauncher {
    override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
      outcomes[WORKFLOW_ID] = authoritativeTerminalOutcome(childReport, WORKFLOW_ID)
      return launchFacts()
    }
  }
  val runner = GoalRunner(
    manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).withWorkflowId(1, WORKFLOW_ID)),
    subtaskLauncher = launcher,
    outcomeStore = outcomes,
    pullRequestPort = RecordingPullRequestPort(),
  )
  val report = runner.run(
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = java.nio.file.Path.of("/tmp/repo"),
      invokedAgentId = INVOKED_AGENT,
    ),
  )
  return report to requireNotNull(outcomes.terminalOutcome(WORKFLOW_ID, "SKILL-56", 1, null))
}

private data class GoalChildParityConfig(
  val gitOperations: RecordingWorkflowGitOperations =
    RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal"),
  val validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  val ensureCommitSha: Boolean = true,
  val codeReviewMode: CodeReviewExecutionMode? = null,
  val parallelReviewAgent: String? = null,
  val acceptanceCriteria: List<String> = listOf("AC-1", "AC-2"),
)

private fun goalChildParityRun(
  launcher: RuntimeRecordingLauncher,
  config: GoalChildParityConfig = GoalChildParityConfig(),
): GoalChildParityRun {
  if (config.ensureCommitSha && config.gitOperations.headCommitShaValue.isBlank()) {
    config.gitOperations.headCommitShaValue = "goal-child-commit"
  }
  val outcomes = RecordingOutcomeStore().apply { seedReviewState(WORKFLOW_ID) }
  val runtime = runnerHarness(
    launcher = launcher,
    validator = config.validator,
    runtimeConfig = RuntimeHarnessConfig(
      branchSetup = BranchSetupTestConfig(gitOperations = config.gitOperations),
      acceptanceCriteria = config.acceptanceCriteria,
    ),
  )
  val childLauncher = RuntimeChildLauncher(runtime.runner, runtime.request(), outcomes)
  val goalRunner = GoalRunner(
    manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).withWorkflowId(1, WORKFLOW_ID)),
    subtaskLauncher = childLauncher,
    outcomeStore = outcomes,
    pullRequestPort = RecordingPullRequestPort(),
  )
  val runRequest = GoalRunnerRunRequest(
    issueKey = "SKILL-56",
    repoRoot = runtime.request().repoRoot,
    invokedAgentId = INVOKED_AGENT,
    codeReviewMode = config.codeReviewMode,
    parallelReviewAgent = config.parallelReviewAgent,
  )
  val report = goalRunner.run(runRequest)
  return GoalChildParityRun(
    report,
    runtime,
    childLauncher.reports,
    childLauncher.continuationRequestCount,
    outcomes,
  ) { goalRunner.run(runRequest) }
}

// A goal-child gaps_found audit whose single gap carries two repair items, so a partial remediation is
// expressible.
private fun auditTwoItemGoalGapOutput(): String = """
  {
    "contract_version": "0.2",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "unmet_criteria": [{"acceptance_criterion_ref":"AC-002","message": "$AUDIT_GAP_MESSAGE"}],
      "audit_repair_plan": {
        "contract_version":"0.2",
        "gaps":[{
          "gap_id":"ac-002-gap-1",
          "acceptance_criterion_ref":"AC-002",
          "acceptance_criterion_text":"The audit gap is repaired.",
          "failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"runtime-kotlin","check_ref":"AC-002"},
          "diagnosis":"Implement and verify the missing behavior in two ordered steps.",
          "affected_boundary":"runtime application",
          "repair_items":[{
            "repair_item_id":"ac-002-gap-1-item-1",
            "intended_outcome":"The behavior is implemented.",
            "implementation_actions":["Reconcile the implementation."],
            "affected_paths_or_symbols":["src/Foo.kt"],
            "required_verification":["Run the focused test."],
            "depends_on":[],
            "status":"pending"
          },{
            "repair_item_id":"ac-002-gap-1-item-2",
            "intended_outcome":"The behavior is covered by a durable test.",
            "implementation_actions":["Add the focused regression."],
            "affected_paths_or_symbols":["src/FooTest.kt"],
            "required_verification":["Run the focused test."],
            "depends_on":["ac-002-gap-1-item-1"],
            "status":"pending"
          }]
        }]
      }
    }
  }
""".trimIndent()

private fun goalRemediationOutput(reportedItemIds: List<String>): String {
  val results = reportedItemIds.joinToString(",") { itemId ->
    """
      {
        "repair_item_id":"$itemId",
        "outcome":"fixed",
        "changed_paths_or_symbols":["src/Foo.kt"],
        "executed_verification":["Focused test passed."],
        "result_evidence":{"observation":"fix_verified","artifact_ref":"src/Foo.kt","check_ref":"AC-002"}
      }
    """.trimIndent()
  }
  return """
    {
      "contract_version": "0.2",
      "phase_id": "implement",
      "status": "completed",
      "summary": "Goal-child remediation reported its repair item results.",
      "produced_outputs": {
        "changed_files":["src/Foo.kt"],
        "reconciled_state":{"reconciled":true},
        "repair_item_results":[$results]
      }
    }
  """.trimIndent()
}

private class RuntimeChildLauncher(
  private val runner: FeatureTaskRuntimeRunner,
  private val template: skillbill.application.model.FeatureTaskRuntimeRunRequest,
  private val outcomes: RecordingOutcomeStore,
) : GoalRunnerSubtaskLauncher {
  val reports: MutableList<FeatureTaskRuntimeRunReport> = mutableListOf()
  var continuationRequestCount: Int = 0
    private set

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    val continuation = assertNotNull(request.skillRunRequest.goalContinuation)
    continuationRequestCount += 1
    val workflowId = assertNotNull(continuation.childWorkflowId)
    val report = runner.run(
      template.copy(
        issueKey = request.skillRunRequest.issueKey,
        workflowId = workflowId,
        repoRoot = request.skillRunRequest.repoRoot,
        invokedAgentId = request.invokedAgentId,
        requestedCodeReviewMode = continuation.codeReviewMode,
        parallelReviewAgent = continuation.parallelReviewAgent,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = continuation.parentIssueKey,
          subtaskId = continuation.subtaskId,
          goalBranch = continuation.goalBranch,
          suppressPr = continuation.suppressPr,
          parentWorkflowId = continuation.parentWorkflowId,
          lastResumableStep = continuation.lastResumableStep,
          codeReviewMode = continuation.codeReviewMode,
          parallelReviewAgent = continuation.parallelReviewAgent,
          reviewBaseline = assertNotNull(continuation.reviewBaseline),
        ),
      ),
    )
    reports += report
    outcomes[workflowId] = authoritativeTerminalOutcome(report, workflowId)
    return launchFacts()
  }
}

private fun authoritativeTerminalOutcome(
  report: FeatureTaskRuntimeRunReport,
  workflowId: String,
): GoalRunnerStoredOutcome {
  val subtaskOutcome = when (report) {
    is FeatureTaskRuntimeRunReport.Completed -> report.subtaskOutcome
    is FeatureTaskRuntimeRunReport.Blocked -> report.subtaskOutcome
    is FeatureTaskRuntimeRunReport.Decomposed -> null
  }
  if (subtaskOutcome != null) {
    return GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.valueOf(subtaskOutcome.status.uppercase()),
      workflowId = subtaskOutcome.workflowId,
      commitSha = subtaskOutcome.commitSha,
      blockedReason = subtaskOutcome.blockedReason,
      lastResumableStep = subtaskOutcome.lastResumableStep,
      suppressPr = true,
    )
  }
  return when (report) {
    is FeatureTaskRuntimeRunReport.Completed -> error("Goal-continuation completed report omitted subtaskOutcome")
    is FeatureTaskRuntimeRunReport.Blocked -> GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.BLOCKED,
      workflowId = workflowId,
      blockedReason = report.blockedReason,
      lastResumableStep = report.lastIncompletePhase,
      suppressPr = true,
    )
    else -> GoalRunnerStoredOutcome(
      status = GoalRunnerTerminalStatus.FAILED,
      workflowId = workflowId,
      blockedReason = (report as FeatureTaskRuntimeRunReport.Decomposed).reason,
      lastResumableStep = "plan",
      suppressPr = true,
    )
  }
}
