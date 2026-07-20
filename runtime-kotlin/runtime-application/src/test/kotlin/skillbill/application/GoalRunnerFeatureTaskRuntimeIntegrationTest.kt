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
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalRunnerFeatureTaskRuntimeIntegrationTest {
  @Test
  fun `goal runner stops when a completed runtime report carries a blocked child outcome`() {
    val parity = goalChildParityRun(
      launcher = defaultPhaseAwareLauncher(),
      gitOperations = RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal"),
      ensureCommitSha = false,
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
    assertEquals(2, reviewCompletions.size)
    assertEquals(1, reviewCompletions.count { it.loopId == "audit_gap" && it.edgeIteration == 1 })
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
  fun `standalone and goal child classify recurring and newly discovered gaps identically`() {
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

    val parity = standaloneAndGoalChildParity(launcher = ::launcher)

    assertIs<GoalRunnerRunReport.Stopped>(parity.report)
    val state = requireNotNull(parity.runtime.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(1, state.progress.recurringGapCount)
    assertEquals(1, state.progress.newGapCount)
    assertEquals(listOf("ac-002-gap-1", "ac-003-gap-1"), state.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
  }

  @Test
  fun `standalone and goal child resume the same durable scenario after a mid-item crash`() {
    fun launcher(
      repository: MidItemScenarioRepository,
      crashAfterLanding: Boolean,
      landingEnabled: Boolean = true,
    ): RuntimeRecordingLauncher {
      return RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        val phaseId = phaseIdFromPrompt(prompt)
        when (phaseId) {
          "audit" -> facts(if (repository.hasEffect("item-1")) auditSatisfiedOutput() else auditGapsOutput())
          "implement" -> {
            if (!prompt.contains("audit_repair_plan")) {
              facts(validJsonOutput(phaseId))
            } else if (!repository.hasEffect("item-1") && !landingEnabled) {
              spawnFailedFacts()
            } else if (!repository.hasEffect("item-1")) {
              repository.landEffect("item-1")
              if (crashAfterLanding) spawnFailedFacts() else facts(validJsonOutput(phaseId))
            } else if (crashAfterLanding) {
              spawnFailedFacts()
            } else {
              facts(validJsonOutput(phaseId))
            }
          }
          else -> facts(validJsonOutput(phaseId))
        }
      }
    }

    val standaloneRepository = MidItemScenarioRepository()
    val standaloneGit = RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal")
      .apply { headCommitShaValue = "goal-child-commit" }
    val standaloneBeforeCrash = runnerHarness(
      launcher = launcher(standaloneRepository, crashAfterLanding = true),
      repository = standaloneRepository.workflow,
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = standaloneGit),
        goalContinuation = parityGoalContinuation(),
      ),
    )
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      standaloneBeforeCrash.runner.run(standaloneBeforeCrash.request()),
    )
    assertEquals(setOf("item-1"), standaloneRepository.effects())
    assertFailsWith<IllegalStateException> { standaloneRepository.landEffect("item-1") }
    standaloneRepository.removeEffect("item-1")
    val standaloneMissingEffect = runnerHarness(
      launcher = launcher(standaloneRepository, crashAfterLanding = false, landingEnabled = false),
      repository = standaloneRepository.workflow,
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = standaloneGit),
        goalContinuation = parityGoalContinuation(),
      ),
    )
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      standaloneMissingEffect.runner.run(standaloneMissingEffect.request()),
    )
    assertTrue(standaloneMissingEffect.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"] == null)
    standaloneRepository.landEffect("item-1")
    val standalone = runnerHarness(
      launcher = launcher(standaloneRepository, crashAfterLanding = false),
      repository = standaloneRepository.workflow,
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = standaloneGit),
        goalContinuation = parityGoalContinuation(),
      ),
    )
    val standaloneCompleted =
      assertIs<FeatureTaskRuntimeRunReport.Completed>(standalone.runner.run(standalone.request()))

    val goalRepository = MidItemScenarioRepository()
    val goalGit = RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal")
      .apply { headCommitShaValue = "goal-child-commit" }
    val outcomes = RecordingOutcomeStore().apply { seedReviewState(WORKFLOW_ID) }
    fun goalAttempt(crashAfterLanding: Boolean): Pair<RunnerHarness, FeatureTaskRuntimeRunReport> {
      val runtime = runnerHarness(
        launcher = launcher(goalRepository, crashAfterLanding),
        repository = goalRepository.workflow,
        runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = goalGit)),
      )
      val childLauncher = RuntimeChildLauncher(runtime.runner, runtime.request(), outcomes)
      val goalRunner = GoalRunner(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).withWorkflowId(1, WORKFLOW_ID)),
        subtaskLauncher = childLauncher,
        outcomeStore = outcomes,
        pullRequestPort = RecordingPullRequestPort(),
      )
      goalRunner.run(
        GoalRunnerRunRequest("SKILL-56", runtime.request().repoRoot, INVOKED_AGENT),
      )
      return runtime to childLauncher.reports.last()
    }
    val (_, crashedGoalReport) = goalAttempt(crashAfterLanding = true)
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(crashedGoalReport)
    assertEquals(setOf("item-1"), goalRepository.effects())
    assertFailsWith<IllegalStateException> { goalRepository.landEffect("item-1") }
    goalRepository.removeEffect("item-1")
    val missingGoalRuntime = runnerHarness(
      launcher = launcher(goalRepository, crashAfterLanding = false, landingEnabled = false),
      repository = goalRepository.workflow,
      runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = goalGit)),
    )
    val missingGoalLauncher = RuntimeChildLauncher(missingGoalRuntime.runner, missingGoalRuntime.request(), outcomes)
    GoalRunner(
      manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).withWorkflowId(1, WORKFLOW_ID)),
      subtaskLauncher = missingGoalLauncher,
      outcomeStore = outcomes,
      pullRequestPort = RecordingPullRequestPort(),
    ).run(GoalRunnerRunRequest("SKILL-56", missingGoalRuntime.request().repoRoot, INVOKED_AGENT))
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(missingGoalLauncher.reports.last())
    assertTrue(missingGoalRuntime.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"] == null)
    goalRepository.landEffect("item-1")
    val (goalRuntime, completedGoalReport) = goalAttempt(crashAfterLanding = false)
    assertIs<FeatureTaskRuntimeRunReport.Completed>(completedGoalReport)
    val standaloneObservation = standalone.goalChildObservation(standaloneCompleted)
    val goalObservation = goalRuntime.goalChildObservation(
      completedGoalReport,
      requireNotNull(outcomes.terminalOutcome(WORKFLOW_ID, "SKILL-56", 1, null)),
    )
    assertEquals(
      standaloneObservation,
      goalObservation,
    )
    assertEquals(standaloneObservation.reviewComposition.size, goalObservation.reviewComposition.size)
    assertTrue(goalObservation.reviewComposition.all { it.contains("durable base `${"0".repeat(40)}`") })
    assertTrue(standalone.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"] != null)
    assertTrue(goalRuntime.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"] != null)
  }

  @Test
  fun `goal child parses every canonical wrapper form into the same run`() {
    val observed = GOAL_CHILD_WRAPPER_FORMS.mapValues { (_, wrap) ->
      val standalone = runnerHarness(
        launcher = wrappedAuditGapLauncher(convergeOnAudit = 2, wrap = wrap),
        validator = FeatureTaskRuntimePhaseOutputValidatorAdapter(),
        runtimeConfig = RuntimeHarnessConfig(goalContinuation = parityGoalContinuation()),
      )
      val standaloneReport =
        assertIs<FeatureTaskRuntimeRunReport.Completed>(standalone.runner.run(standalone.request()))
      val standaloneObservation = standalone.goalChildObservation(standaloneReport)
      val parity = goalChildParityRun(
        launcher = wrappedAuditGapLauncher(convergeOnAudit = 2, wrap = wrap),
        validator = FeatureTaskRuntimePhaseOutputValidatorAdapter(),
      )
      assertIs<GoalRunnerRunReport.Completed>(parity.report)
      parity.runtime.goalChildObservation(parity.childReports.last(), parity.authoritativeOutcome()).also {
        assertEquals(
          standaloneObservation,
          it,
          "standalone and goal-child observations must match",
        )
        assertEquals(standaloneObservation.reviewComposition.size, it.reviewComposition.size)
        assertTrue(it.reviewComposition.all { review -> review.contains("durable base `${"0".repeat(40)}`") })
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

private class MidItemScenarioRepository {
  val workflow = InMemoryRuntimeWorkflowRepository()
  private val landedEffects = linkedSetOf<String>()

  fun hasEffect(effect: String): Boolean = effect in landedEffects

  fun landEffect(effect: String) {
    check(landedEffects.add(effect)) { "duplicate repository effect: $effect" }
  }

  fun removeEffect(effect: String) {
    check(landedEffects.remove(effect)) { "missing repository effect: $effect" }
  }

  fun effects(): Set<String> = landedEffects.toSet()
}

private fun parityGoalContinuation(
  codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DELEGATED,
  parallelReviewAgent: String? = null,
) = FeatureTaskRuntimeGoalContinuationContext(
  parentIssueKey = "SKILL-56",
  subtaskId = 1,
  goalBranch = "feat/SKILL-56-goal",
  suppressPr = true,
  codeReviewMode = codeReviewMode,
  parallelReviewAgent = parallelReviewAgent,
  reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
)

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
): GoalChildParityRun {
  val standaloneGit = gitOperations().apply {
    if (headCommitShaValue.isBlank()) headCommitShaValue = "goal-child-commit"
  }
  val standalone = runnerHarness(
    launcher = launcher(),
    runtimeConfig = RuntimeHarnessConfig(
      branchSetup = BranchSetupTestConfig(gitOperations = standaloneGit),
      goalContinuation = parityGoalContinuation(codeReviewMode, parallelReviewAgent),
    ),
  )
  val standaloneReport = standalone.runner.run(standalone.request())
  val goalChild = goalChildParityRun(
    launcher = launcher(),
    gitOperations = gitOperations(),
    codeReviewMode = codeReviewMode,
    parallelReviewAgent = parallelReviewAgent,
  )
  assertEquals(
    standalone.goalChildObservation(standaloneReport),
    goalChild.runtime.goalChildObservation(goalChild.childReports.last(), goalChild.authoritativeOutcome()),
    "standalone and goal-child durable scenario observations must match",
  )
  val standaloneReviews = standalone.goalChildObservation(standaloneReport).reviewComposition
  val goalReviews = goalChild.runtime
    .goalChildObservation(goalChild.childReports.last(), goalChild.authoritativeOutcome()).reviewComposition
  assertEquals(standaloneReviews.size, goalReviews.size)
  assertTrue(goalReviews.all { it.contains("durable base `${"0".repeat(40)}`") })
  assertTrue(goalReviews.all { it.contains("committed, staged, unstaged") })
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

private fun goalChildParityRun(
  launcher: RuntimeRecordingLauncher,
  gitOperations: RecordingWorkflowGitOperations =
    RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal"),
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  ensureCommitSha: Boolean = true,
  codeReviewMode: CodeReviewExecutionMode? = null,
  parallelReviewAgent: String? = null,
): GoalChildParityRun {
  if (ensureCommitSha && gitOperations.headCommitShaValue.isBlank()) {
    gitOperations.headCommitShaValue = "goal-child-commit"
  }
  val outcomes = RecordingOutcomeStore().apply { seedReviewState(WORKFLOW_ID) }
  val runtime = runnerHarness(
    launcher = launcher,
    validator = validator,
    runtimeConfig = RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(gitOperations = gitOperations)),
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
    codeReviewMode = codeReviewMode,
    parallelReviewAgent = parallelReviewAgent,
  )
  val report = goalRunner.run(runRequest)
  return GoalChildParityRun(report, runtime, childLauncher.reports, outcomes) { goalRunner.run(runRequest) }
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

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    val continuation = assertNotNull(request.skillRunRequest.goalContinuation)
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
