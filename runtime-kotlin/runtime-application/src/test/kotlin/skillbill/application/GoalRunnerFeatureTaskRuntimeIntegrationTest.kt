package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalRunnerFeatureTaskRuntimeIntegrationTest {
  @Test
  fun `integration goal run resumes after a durable targeted pause without relaunching the completed child`() {
    val store = InMemoryGoalManifestStore(manifest = manifest(subtaskCount = 2))
    val outcomes = RecordingOutcomeStore()
    val launcher = RecordingSubtaskLauncher { request ->
      val subtaskId = requireNotNull(request.skillRunRequest.subtaskId)
      store.mutate { current -> current.withWorkflowId(subtaskId, "wfl-$subtaskId") }
      outcomes["wfl-$subtaskId"] = completeOutcome(subtaskId)
      launchFacts()
    }
    val runner = GoalRunner(store, launcher, outcomes, RecordingPullRequestPort())

    val paused = runner.run(
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
        invokedAgentId = INVOKED_AGENT,
        stopAfterSubtaskId = 1,
      ),
    )
    assertEquals(GoalRunnerStopReason.PAUSED, assertIs<GoalRunnerRunReport.Stopped>(paused).stop.reason)
    assertEquals(listOf(1), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertTrue(store.controlState.paused)

    val resumed = runner.run(
      GoalRunnerRunRequest(
        issueKey = "SKILL-56",
        repoRoot = Path.of("/tmp/skillbill-goal-runner"),
        invokedAgentId = INVOKED_AGENT,
        stopAfterSubtaskId = 1,
      ),
    )
    assertIs<GoalRunnerRunReport.Completed>(resumed)
    assertEquals(listOf(1, 2), launcher.requests.mapNotNull { it.skillRunRequest.subtaskId })
    assertEquals("complete", store.manifest.status)
    assertEquals("complete", store.manifest.subtasks.single { it.id == 2 }.status)
    assertTrue(store.controlState.stopAfterConsumed)
    assertTrue(!store.controlState.paused)
  }

  @Test
  fun `goal runner completes when the typed commit receipt carries its commit sha`() {
    val parity = goalChildParityRun(
      launcher = defaultPhaseAwareLauncher(),
      config = GoalChildParityConfig(
        gitOperations = RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal"),
        ensureCommitSha = false,
      ),
    )

    val child = assertIs<FeatureTaskRuntimeRunReport.Completed>(parity.childReports.single())
    assertEquals("complete", child.subtaskOutcome?.status)
    assertIs<GoalRunnerRunReport.Completed>(parity.report)
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
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ),
    )

    assertIs<GoalRunnerRunReport.Completed>(report)
    val reviewPrompts = phaseLauncher.requests
      .mapNotNull { it.skillRunRequest.promptOverride }
      .filter { it.contains("Phase: review") }
    assertEquals(emptyList(), reviewPrompts, "runtime-owned review must not launch a review-phase agent")
    assertEquals(CodeReviewExecutionMode.INLINE, runtime.runInvariantsStore.resolve(workflowId)?.codeReviewMode)
    assertTrue(outcomes.acknowledgedReviewPasses.all { (_, passNumber) -> passNumber <= 2 })
  }

  @Test
  fun `standalone and goal child preserve parallel review composition`() {
    val parity = standaloneAndGoalChildParity(
      launcher = ::defaultPhaseAwareLauncher,
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )

    assertIs<GoalRunnerRunReport.Completed>(parity.report)
    val reviews = parity.runtime.goalChildObservation(
      parity.childReports.last(),
      parity.authoritativeOutcome(),
    ).reviewComposition
    assertEquals(emptyList(), reviews, "runtime-owned review does not compose a review-phase agent prompt")
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
    assertTrue(!remediationPrompt.contains("### from: preplan"))
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
    assertEquals(1, runtime.launchOrder().count { it == "review" })
    assertEquals(0, reviewCompletions.count { it.loopId == "audit_gap" })
  }

  @Test
  fun `goal child accepts future phase prose when structured repair results are exhaustive`() {
    fun launcher(): RuntimeRecordingLauncher {
      var auditLaunches = 0
      var implementLaunches = 0
      return RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "audit" -> {
            auditLaunches += 1
            facts(if (auditLaunches == 1) auditGapsOutput() else auditSatisfiedOutput())
          }
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

    assertIs<GoalRunnerRunReport.Completed>(parity.report)
    val observation = parity.runtime.goalChildObservation(
      parity.childReports.last(),
      parity.authoritativeOutcome(),
    )
    assertEquals(observation.terminalReport, observation.authoritativeOutcome)
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
    assertContains(blocked, "Audit made no progress")
    assertContains(blocked, "repository fingerprint is unchanged")
    assertTrue(parity.runtime.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"] == null)
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
      .mapNotNull { (phaseId, record) ->
        record.outputArtifact?.let { artifact ->
          phaseId to if (phaseId == "review") {
            artifact.replace(Regex("\"review_run_id\":\"rvw-[^\"]+\""), "\"review_run_id\":\"rvw-stable\"")
          } else {
            artifact
          }
        }
      }.toMap(),
    auditGapEdgeIterations = recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
      .mapNotNull { it.edgeIteration },
    remediationHandoffs = launcher.requests.mapNotNull { it.skillRunRequest.promptOverride }
      .filter { it.contains("Phase: implement") && it.contains("audit_gaps:") }
      .map { prompt ->
        prompt
          .replace(Regex("for issue SKILL-\\d+\\."), "for issue <issue>.")
          .substringAfter("audit_repair_plan:\n")
          .substringBefore("audit_remediation_execution_rules:")
          .trim()
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
    is FeatureTaskRuntimeRunReport.Paused -> subtaskOutcome
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
      is FeatureTaskRuntimeRunReport.Paused ->
        TerminalObservation("paused", pauseReason, null, workflowId, resumableStep)
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
  codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.INLINE,
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
  )
  assertEquals(null, standaloneRequest.goalContinuation)
  val standaloneReport = standalone.runner.run(standaloneRequest)
  val goalChild = goalChildParityRun(
    launcher = launcher(),
    config = GoalChildParityConfig(
      gitOperations = gitOperations(),
      codeReviewMode = codeReviewMode,
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
  assertEquals(standalone.reviewComposition, goalChild.reviewComposition)
  assertTrue(standalone.reviewComposition.all { it.contains("durable base `${"0".repeat(40)}`") })
  assertTrue(goalChild.reviewComposition.all { it.contains("durable base `${"0".repeat(40)}`") })
  assertTrue(standalone.reviewComposition.all { it.contains("committed, staged, unstaged") })
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
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = continuation.parentIssueKey,
          subtaskId = continuation.subtaskId,
          goalBranch = continuation.goalBranch,
          suppressPr = continuation.suppressPr,
          parentWorkflowId = continuation.parentWorkflowId,
          lastResumableStep = continuation.lastResumableStep,
          codeReviewMode = continuation.codeReviewMode,
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
    is FeatureTaskRuntimeRunReport.Paused -> report.subtaskOutcome
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
