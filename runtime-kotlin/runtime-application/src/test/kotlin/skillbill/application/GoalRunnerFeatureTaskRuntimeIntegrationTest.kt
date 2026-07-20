package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.goalrunner.GoalRunner
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.GoalRunnerRunRequest
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
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
    var implementLaunches = 0
    val parity = goalChildParityRun(
      launcher = RuntimeRecordingLauncher { request ->
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
      },
    )

    assertIs<GoalRunnerRunReport.Stopped>(parity.report)
    val blocked = assertNotNull(parity.blockedChildReason())
    assertContains(blocked, "exact repair_item_result identifier equality")
    assertContains(blocked, "ac-002-gap-1-item-2")
    val repairState = requireNotNull(parity.runtime.recorder.loadAuditRepairState(WORKFLOW_ID))
    assertEquals(listOf("ac-002-gap-1"), repairState.unresolvedGapLedger.unresolvedGaps.map { it.gapId })
  }

  @Test
  fun `goal child rejects a remediation deferring carried work to a later phase`() {
    var implementLaunches = 0
    val parity = goalChildParityRun(
      launcher = RuntimeRecordingLauncher { request ->
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
      },
    )

    assertIs<GoalRunnerRunReport.Stopped>(parity.report)
    assertContains(assertNotNull(parity.blockedChildReason()), "later phase")
  }

  @Test
  fun `goal child blocks on a non-progressing equivalent gap set`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal")
      .apply { repositoryFingerprintValue = "unchanged" }
    val parity = goalChildParityRun(launcher = auditGapLauncher(convergeOnAudit = 3), gitOperations = git)

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
  fun `goal child parses every canonical wrapper form into the same run`() {
    val observed = GOAL_CHILD_WRAPPER_FORMS.mapValues { (_, wrap) ->
      val standalone = runnerHarness(
        launcher = wrappedAuditGapLauncher(convergeOnAudit = 2, wrap = wrap),
        validator = FeatureTaskRuntimePhaseOutputValidatorAdapter(),
      )
      assertIs<FeatureTaskRuntimeRunReport.Completed>(standalone.runner.run(standalone.request()))
      val standaloneObservation = standalone.goalChildObservation()
      val parity = goalChildParityRun(
        launcher = wrappedAuditGapLauncher(convergeOnAudit = 2, wrap = wrap),
        validator = FeatureTaskRuntimePhaseOutputValidatorAdapter(),
      )
      assertIs<GoalRunnerRunReport.Completed>(parity.report)
      parity.runtime.goalChildObservation().also {
        assertEquals(standaloneObservation, it, "standalone and goal-child observations must match")
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
)

private fun RunnerHarness.goalChildObservation(): GoalChildObservation = GoalChildObservation(
  phaseOrder = launchedPromptPhaseOrder().filterNot { it == "pr" },
  persistedOutputs = recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    .filterKeys { it != "pr" }
    .mapNotNull { (phaseId, record) -> record.outputArtifact?.let { phaseId to it } }.toMap(),
  auditGapEdgeIterations = recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
    .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "audit_gap" }
    .mapNotNull { it.edgeIteration },
)

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
)

private fun GoalChildParityRun.blockedChildReason(): String? =
  (report as? GoalRunnerRunReport.Stopped)?.stop?.blockedReason

private fun goalChildParityRun(
  launcher: RuntimeRecordingLauncher,
  gitOperations: RecordingWorkflowGitOperations =
    RecordingWorkflowGitOperations(currentBranchValue = "feat/SKILL-56-goal"),
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  ensureCommitSha: Boolean = true,
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
  val report = goalRunner.run(
    GoalRunnerRunRequest(
      issueKey = "SKILL-56",
      repoRoot = runtime.request().repoRoot,
      invokedAgentId = INVOKED_AGENT,
    ),
  )
  return GoalChildParityRun(report, runtime, childLauncher.reports)
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
      suppressPr = true,
    )
  }
}
