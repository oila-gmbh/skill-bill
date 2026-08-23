package skillbill.application

import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.FeatureSpecPreparationRuntime
import skillbill.application.featuretask.FeatureSpecPreparationWriter
import skillbill.application.featuretask.FeatureTaskRuntimeAgentResolver
import skillbill.application.featuretask.FeatureTaskRuntimeAttemptBudgets
import skillbill.application.featuretask.FeatureTaskRuntimeBranchSetupRunner
import skillbill.application.featuretask.FeatureTaskRuntimeCrashReconciler
import skillbill.application.featuretask.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeDecompositionPlanner
import skillbill.application.featuretask.FeatureTaskRuntimeFindingVerificationBoundaryMemory
import skillbill.application.featuretask.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeLifecycleTelemetry
import skillbill.application.featuretask.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.application.featuretask.FeatureTaskRuntimePhaseGates
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimePlanningStopper
import skillbill.application.featuretask.FeatureTaskRuntimeRunInvariantsStore
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeSpecGate
import skillbill.application.featuretask.FeatureTaskRuntimeStatusService
import skillbill.application.featuretask.GoalContinuationStateRecordRequest
import skillbill.application.featuretask.GoalSubtaskReviewInputBlocked
import skillbill.application.featuretask.GoalSubtaskReviewInputReady
import skillbill.application.featuretask.RemediationBaseCoherent
import skillbill.application.featuretask.SpecSourceResolver
import skillbill.application.featuretask.phaseDeclaration
import skillbill.application.featuretask.reconcileCheckpointPathInventory
import skillbill.application.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.model.FeatureTaskRuntimeRunEvent
import skillbill.application.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.model.FeatureTaskRuntimeRunReport
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.model.FeatureTaskRuntimeStatusRequest
import skillbill.application.review.SpecIntentProjectionExtractor
import skillbill.application.review.SpecIntentProjectionResolver
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.workflow.repoRoot
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.error.WorkflowIssueKeyConflictError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.LearningRepository
import skillbill.ports.persistence.LifecycleTelemetryRepository
import skillbill.ports.persistence.ReviewRepository
import skillbill.ports.persistence.TelemetryOutboxRepository
import skillbill.ports.persistence.TelemetryReconciliationRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureImplementSessionSummary
import skillbill.ports.persistence.model.FeatureTaskRuntimeAuditGenerationRow
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.FeatureVerifySessionSummary
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.workflow.CheckpointHistoryGitOperations
import skillbill.ports.workflow.CheckpointHistoryGitOperationsProvider
import skillbill.ports.workflow.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.NoopWorkflowGitOperations
import skillbill.ports.workflow.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.RepositoryFingerprintGitOperationsProvider
import skillbill.ports.workflow.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.RepositoryOwnedPathsGitOperationsProvider
import skillbill.ports.workflow.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.RuntimePhaseFileManifestGitOperationsProvider
import skillbill.ports.workflow.ScopedStagingGitOperations
import skillbill.ports.workflow.ScopedStagingGitOperationsProvider
import skillbill.ports.workflow.SpecScratchStore
import skillbill.ports.workflow.WorkflowGitOperations
import skillbill.ports.workflow.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.model.GoalSubtaskReviewInputFailureReason
import skillbill.ports.workflow.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.model.WorkflowWorktreeActivityResult
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.TelemetrySettings
import skillbill.workflow.FeatureTaskRuntimeBuildReceiptValidator
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.NoopFeatureTaskRuntimeBuildReceiptValidator
import skillbill.workflow.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.GoalObservabilityDiffStat
import skillbill.workflow.model.GoalObservabilitySelectedDiffHunks
import skillbill.workflow.model.SpecSource
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewCompactFinding
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewDisposition
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_CHECKPOINT_IDENTITY_VERSION
import skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointRefName
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class FeatureTaskRuntimeRunnerTest {
  @Test
  fun `runs phases deterministically through terminal pr phase order`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(COMPLETED_PHASES_CLEAN_RUN, completed.completedPhaseIds)
    assertEquals(
      AGENT_LAUNCHED_PHASES,
      harness.launchedPhaseOrder(),
    )
    assertEquals(
      COMPLETED_PHASES_CLEAN_RUN,
      harness.launchOrder(),
    )
  }

  @Test
  fun `single-spec completion reconciles the spec Agent line with the ledger-derived finalizing agent`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val write = harness.specStatusWriter.writes.single()
    assertEquals(SPEC_REFERENCE, write.first.toString())
    assertEquals(
      phaseAgent("pr"),
      write.second,
      "the Agent line must carry the ledger-derived finalizing agent (the agent that ran the terminal pr phase)",
    )
  }

  @Test
  fun `goal-continuation completion does not reconcile a single-spec Agent line`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-no-spec-line")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = "measured-head-sha" }
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))

    harness.runner.run(harness.request())

    assertTrue(
      harness.specStatusWriter.writes.isEmpty(),
      "a goal-continuation subtask run never stamps the single-spec Agent line",
    )
  }

  @Test
  fun `runtime run against a prose-mode workflow blocks with an actionable reason and launches nothing`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedProseModeWorkflow()

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "was created in 'prose' mode")
    assertContains(blocked.blockedReason, "reset the subtask")
    assertTrue(harness.launcher.requests.isEmpty())
  }

  @Test
  fun `runtime issue-key reopen conflict fails before run events or agents start`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID, issueKey = ISSUE_KEY)

    assertFailsWith<WorkflowIssueKeyConflictError> {
      harness.runner.run(harness.request().copy(issueKey = "SKILL-118"))
    }

    assertTrue(harness.events.isEmpty())
    assertTrue(harness.launcher.requests.isEmpty())
  }

  @Test
  fun `blocked phase output stops the run and does not advance to pr`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "commit_push") COMMIT_PUSH_BLOCKED_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("commit_push", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "Validation failed before commit.")
    assertTrue(harness.launchedPhaseOrder().none { it == "pr" })
    assertTrue(
      harness.events.any { event ->
        event is FeatureTaskRuntimeRunEvent.PhaseBlocked && event.phaseId == "commit_push"
      },
    )
  }

  @Test
  fun `non-retryable review policy conflict re-blocks on resume without relaunch`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    harness.seedBlockedPhase(
      "review",
      attemptCount = 1,
      phaseAgent("review"),
      "Review policy cannot run for this scope.",
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NON_RETRYABLE_POLICY_CONFLICT,
    )

    val resumed = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("review", resumed.lastIncompletePhase)
    assertTrue(harness.launchOrder().none { it == "review" })
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("non_retryable_policy_conflict", record.failureDisposition?.wireValue)
  }

  @Test
  fun `retryable review failure uses the bounded in-phase retry`() {
    var auditLaunches = 0
    val retryableFailure = """
      {
        "contract_version":"0.2",
        "phase_id":"audit",
        "status":"failed",
        "failure_disposition":"retryable",
        "summary":"Transient audit preparation failed.",
        "produced_outputs":{"blocking_reasons":["Temporary input unavailable."]}
      }
    """.trimIndent()
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit") auditLaunches += 1
        facts(if (phaseId == "audit" && auditLaunches == 1) retryableFailure else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(2, auditLaunches)
  }

  @Test
  fun `phase completes and records manifest when it introduces another issue spec`() {
    val git = RecordingWorkflowGitOperations()
    git.worktreeStatusSequence.addAll(
      listOf("", "?? .feature-specs/SKILL-124-sqldelight-runtime-persistence/spec.md"),
    )
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
      ),
    )

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(COMPLETED_PHASES_CLEAN_RUN, completed.completedPhaseIds)
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["preplan"])
    assertEquals(
      listOf(".feature-specs/SKILL-124-sqldelight-runtime-persistence/spec.md"),
      record.fileManifestIntroduced,
    )
  }

  @Test
  fun `phase completes when it commits another issue spec`() {
    val git = RecordingWorkflowGitOperations()
    git.runtimePhaseHeadCommitSequence.addAll(listOf("before", "after"))
    git.changedPathsBetweenCommitsValue =
      ".feature-specs/SKILL-124-sqldelight-runtime-persistence/spec.md"
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
      ),
    )

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(COMPLETED_PHASES_CLEAN_RUN, completed.completedPhaseIds)
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["preplan"])
    assertEquals(
      listOf(".feature-specs/SKILL-124-sqldelight-runtime-persistence/spec.md"),
      record.fileManifestIntroduced,
    )
  }

  @Test
  fun `validation phase output block keeps repairing instead of stopping task`() {
    var validateLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "validate") {
          validateLaunches += 1
        }
        facts(if (phaseId == "validate" && validateLaunches < 3) VALIDATE_BLOCKED_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(COMPLETED_PHASES_CLEAN_RUN, completed.completedPhaseIds)
    assertEquals(3, harness.launchedPhaseOrder().count { it == "validate" })
    assertTrue(
      harness.events.none { event -> event is FeatureTaskRuntimeRunEvent.PhaseBlocked && event.phaseId == "validate" },
    )
    val validateRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"])
    assertEquals("completed", validateRecord.status)
    assertEquals(3, validateRecord.attemptCount)
  }

  @Test
  @Suppress("LongMethod")
  fun `each phase briefing includes unconditional run-invariants latest upstream and derived diff for review`() {
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = SPEC_REFERENCE,
      featureSize = FeatureTaskRuntimeFeatureSize.SMALL,
      acceptanceCriteria = listOf("AC-1", "AC-2"),
      mandatesAndOverrides = listOf("mandate-X"),
    )
    val recorded = listOf(
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("preplan", 1, PREPLAN_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("plan", 1, PLAN_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("implement", 1, IMPLEMENT_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("audit", 1, VALID_AUDIT_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("review", 1, VALID_REVIEW_OUTPUT),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput(
        "verify_findings",
        1,
        VALID_VERIFY_FINDINGS_OUTPUT,
      ),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput("validate", 1, validJsonOutput("validate")),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput(
        "write_history",
        1,
        validJsonOutput("write_history"),
      ),
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput(
        "commit_push",
        1,
        FINALISED_COMMIT_PUSH_OUTPUT,
      ),
    )

    val briefings = COMPLETED_PHASES_CLEAN_RUN.associateWith { phaseId ->
      val declaration =
        skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(
          phaseId,
          invariants.featureSize,
        )
      val handoff = skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract.assembleHandoff(
        declaration = declaration,
        runInvariants = invariants,
        recordedOutputs = recorded,
        // audit's receipt edge refreshes from a resolved checkpoint (AC-012).
        repositoryCheckpoint = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint(
          fingerprint = "fixture-checkpoint-1",
        ),
      )
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    }

    briefings.forEach { (phaseId, briefing) ->
      assertEquals(SPEC_REFERENCE, briefing.specReference, "spec reference for $phaseId")
      assertEquals("SMALL", briefing.featureSize, "feature size for $phaseId")
      assertEquals(listOf("AC-1", "AC-2"), briefing.acceptanceCriteria, "criteria for $phaseId")
      assertContains(briefing.briefingText, "feature_size: SMALL")
      assertContains(briefing.briefingText, SPEC_REFERENCE)
      // Identity, ceremony, and the policy mandates reach every phase, including the finalization
      // phases the mandates most directly govern.
      assertContains(briefing.briefingText, "mandate-X", message = "mandates missing for $phaseId")
    }
    // plan and implement receive bounded planning projections rather than coarse upstream receipts.
    assertContains(briefings.getValue("plan").briefingText, "affected_boundaries")
    assertContains(briefings.getValue("implement").briefingText, "Fixture task.")
    assertEquals(listOf("current_unit_of_work"), briefings.getValue("review").derivedContextKeys)
    assertContains(briefings.getValue("review").briefingText, "current_unit_of_work")
    assertPrKeepsSelfReadBranchDiff(briefings.getValue("pr"))
  }

  private fun assertPrKeepsSelfReadBranchDiff(briefing: FeatureTaskRuntimePhaseLaunchBriefing) {
    // PR is split off the review diff key: it keeps a self-read instruction and is not delivered the
    // shared evidence projection (AC-003/AC-005).
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF),
      briefing.derivedContextKeys,
    )
    assertContains(briefing.briefingText, "pr_branch_diff")
    assertContains(briefing.briefingText, "read the branch diff yourself")
  }

  @Test
  fun `schema gate rejection on a non-fix-loop phase blocks without advancing`() {
    // write_history is downstream of implement and does not retry invalid output; a schema-invalid
    // output blocks immediately.
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("write_history")),
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("write_history", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "does not participate in a fix loop")
    assertEquals(
      listOf("preplan", "plan", "implement", "audit", "review", "verify_findings", "validate"),
      blocked.completedPhaseIds,
    )
    assertEquals(
      listOf("preplan", "plan", "implement", "audit", "verify_findings", "validate", "write_history"),
      harness.launchedPhaseOrder(),
    )
    val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "write_history" }
    assertContentEquals(validJsonOutput("write_history").encodeToByteArray(), diagnostic.payload)
  }

  @Test
  fun `review schema correction continues past the former three-attempt cap`() {
    var reviewAttempts = 0
    val harness = runnerHarness(
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel == "review") {
            reviewAttempts += 1
          }
        }
      },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, reviewAttempts)
    val launchedPhases = harness.launchOrder()
    assertEquals(1, launchedPhases.count { it == "plan" })
    assertEquals(1, launchedPhases.count { it == "implement" })
    assertEquals(1, launchedPhases.count { it == "review" })
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "review" })
  }

  @Test
  fun `review fix loop advances to validate after one fix round`() {
    val harness = runnerHarness(runtimeConfig = reviewFixRuntimeConfig(2))

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchOrder().count { it == "review" })
    assertEquals(1, harness.launchOrder().count { it == "implement_fix" })
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("completed", reviewRecord.status)
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "review" })
  }

  @Test
  fun `malformed serialization retries do not consume semantic repair attempts`() {
    var reviewAttempts = 0
    val harness = runnerHarness(
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel != "review") return
          reviewAttempts += 1
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(1, reviewAttempts)
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "review" })
  }

  @Test
  fun `a schema-gate rejection records exact evidence and threads a payload-free reason into retry`() {
    var reviewAttempts = 0
    val harness = runnerHarness(
      validator = object : FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel == "review") reviewAttempts += 1
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(1, reviewAttempts)
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "review" })
    assertEquals(emptyList(), harness.io.database.rejectedDiagnostics().filter { it.metadata.phaseId == "review" })
  }

  @Test
  fun `goal-child schema rejection records one diagnostic before retrying`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-child-diagnostic")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    var auditAttempts = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "audit" && auditAttempts++ == 0) {
          facts("""{"private":"goal-child-secret"}""")
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
      validator = object : skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator {
        override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
          if (sourceLabel == "audit" && phaseOutputText.contains("goal-child-secret")) {
            throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
              sourceLabel = sourceLabel,
              reason = "private payload",
              payloadFreeReason = "status: does not have a value in the enumeration",
            )
          }
        }
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
        useRealDecompositionPlanner = true,
      ),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertEquals("audit", blocked.lastIncompletePhase)

    val diagnostic = harness.io.database.rejectedDiagnostics().single { it.metadata.phaseId == "audit" }
    assertContentEquals("""{"private":"goal-child-secret"}""".encodeToByteArray(), diagnostic.payload)
    assertEquals(1, diagnostic.metadata.attempt)
  }

  @Test
  fun `per-phase agent resolution honors override then per-phase then invoked default`() {
    val harness = runnerHarness(
      agentAssignment = FeatureTaskRuntimeAgentAssignment(
        perPhaseAgentIds = mapOf("review" to "claude"),
      ),
    )

    harness.runner.run(harness.request())

    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    assertEquals(INVOKED_AGENT, records.getValue("plan").resolvedAgentId)
    assertEquals("claude", records.getValue("review").resolvedAgentId)
  }

  @Test
  fun `run-wide override wins over per-phase and invoked for every phase`() {
    val harness = runnerHarness(
      agentAssignment = FeatureTaskRuntimeAgentAssignment(
        perPhaseAgentIds = mapOf("review" to "claude"),
        override = "cursor",
      ),
    )

    harness.runner.run(harness.request())

    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    AGENT_LAUNCHED_PHASES.forEach { phaseId ->
      assertEquals("cursor", records.getValue(phaseId).resolvedAgentId, "override must win for $phaseId")
    }
  }

  @Test
  fun `invoked agent is the always-present default and there is no hardcoded codex default`() {
    // The resolver order is per-phase entry -> invoking agent id; env is applied upstream at the
    // CLI boundary, not here, so an absent per-phase entry falls back to the invoked agent only.
    val resolved = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = "plan",
      assignment = FeatureTaskRuntimeAgentAssignment(),
      invokedAgentId = INVOKED_AGENT,
    )
    assertEquals(INVOKED_AGENT, resolved.invokedAgentId)
    assertEquals(INVOKED_AGENT, resolved.resolvedAgentId)

    val perPhase = FeatureTaskRuntimeAgentResolver.resolve(
      phaseId = "review",
      assignment = FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = mapOf("review" to "claude")),
      invokedAgentId = INVOKED_AGENT,
    )
    assertEquals("claude", perPhase.resolvedAgentId)
  }

  @Test
  fun `resume restarts from last incomplete phase and restores upstream outputs`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      listOf("audit", "verify_findings", "validate", "write_history", "commit_push", "pr"),
      harness.launchedPhaseOrder(),
    )
    assertEquals(
      listOf("audit", "review", "verify_findings", "validate", "write_history", "commit_push", "pr"),
      harness.launchOrder(),
    )

    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    val auditBriefing = requireNotNull(briefings["audit"]) { "audit briefing must be persisted" }
    // audit receives the bounded plan commitment and implementation receipt, not the complete envelopes.
    assertContains(auditBriefing.briefingText, "task_commitments")
    assertContains(auditBriefing.briefingText, "changed_paths")
    assertFalse(auditBriefing.briefingText.contains("Phase produced a validated output."))
    // Audit runs before review, so it no longer carries any review output.
    assertFalse(auditBriefing.hasUpstreamReceipt("review"))
    val reviewBriefing = requireNotNull(briefings["review"]) { "review briefing must be persisted" }
    assertContains(reviewBriefing.briefingText, "clearance_status: satisfied")
    assertFalse(reviewBriefing.briefingText.contains(IMPLEMENT_OUTPUT))
    val historyBriefing = requireNotNull(briefings["write_history"]) { "history briefing must be persisted" }
    assertContains(historyBriefing.briefingText, "boundary_candidates")
    assertContains(historyBriefing.briefingText, "validation_status: passed")
    val commitBriefing = requireNotNull(briefings["commit_push"]) { "commit briefing must be persisted" }
    assertContains(commitBriefing.briefingText, "gate_attestations")
    assertContains(commitBriefing.briefingText, "decisions_recorded")
    val prBriefing = requireNotNull(briefings["pr"]) { "pr briefing must be persisted" }
    assertContains(prBriefing.briefingText, "commit_sha")
  }

  @Test
  fun `resume skips completed preplan and restores its digest into plan briefing`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      listOf("plan", "implement", "audit", "verify_findings", "validate", "write_history", "commit_push", "pr"),
      harness.launchedPhaseOrder(),
    )
    assertTrue(harness.launchedPhaseOrder().none { it == "preplan" })
    val planBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["plan"])
    // plan receives the bounded preplanning digest, not preplan's complete envelope.
    assertContains(planBriefing.briefingText, "### from: preplan")
    assertContains(planBriefing.briefingText, "affected_boundaries")
    assertContains(planBriefing.briefingText, "Fixture risk.")
    assertFalse(planBriefing.briefingText.contains("Phase produced a validated output."))
  }

  @Test
  fun `resume re-runs legacy completed plan when preplan output is absent`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(AGENT_LAUNCHED_PHASES, harness.launchedPhaseOrder())
    val planBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["plan"])
    assertContains(planBriefing.briefingText, "### from: preplan")
    assertContains(planBriefing.briefingText, "affected_boundaries")
  }

  @Test
  fun `resume of a fix-loop phase that already has several attempts relaunches rather than re-blocking`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "running", 3, phaseAgent("implement"), outputArtifact = null)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(harness.launchedPhaseOrder().contains("implement"))
    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("completed", implementRecord.status)
  }

  @Test
  fun `resume of a fix-loop phase at attempt one resumes at iteration two`() {
    // F-001: review persisted as running at attemptCount=1 (no valid artifact) resumes at the
    // next attempt (iteration 2) rather than resetting to iteration 1.
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("review", "running", 1, phaseAgent("review"), outputArtifact = null)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    // Completed on the resumed attempt; attempt count is 2 (resumed from durable attempt 1).
    assertEquals(2, reviewRecord.attemptCount)
    assertEquals("completed", reviewRecord.status)
  }

  @Test
  fun `resume of a non-fix-loop phase with a durable blocked record re-blocks without relaunching`() {
    // F-002: a non-fix-loop phase persisted with a terminal blocked record (the durable marker
    // that survives ledger pruning) re-blocks on resume without launching the agent again.
    // write_history is non-fix-loop (implement is now a bounded fix-loop phase).
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("review", "completed", 1, phaseAgent("review"), VALID_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    harness.seedPhase("validate", "completed", 1, phaseAgent("validate"), VALID_OUTPUT)
    harness.seedBlockedPhase("write_history", attemptCount = 1, phaseAgent("write_history"), "history gate failed")

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("write_history", blocked.lastIncompletePhase)
    assertTrue(harness.launchedPhaseOrder().none { it == "write_history" })
  }

  @Test
  fun `resume of a fix-loop phase with a durable blocked record relaunches until the attempt cap`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("review", "completed", 1, phaseAgent("review"), VALID_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    harness.seedBlockedPhase("validate", attemptCount = 1, phaseAgent("validate"), "validation gate failed")

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(harness.launchedPhaseOrder().contains("validate"))
    val validateRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"])
    assertEquals("completed", validateRecord.status)
    assertEquals(2, validateRecord.attemptCount)
  }

  @Test
  fun `gate repair completed without gate_run_count does not fail consumer-projection`() {
    // Repair agents must not invent gate_run_count; consumer-projection used to reject that
    // completed segment before the coordinator could re-run the gate and settle measured counts.
    val gateCalls = java.util.concurrent.atomic.AtomicInteger(0)
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        validationGateRunner = failThenPassValidationGateRunner(gateCalls),
        validationGatePlatformManifests = listOf(kotlinPackWithValidationGate()),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(
          if (phaseId == "validate") {
            VALIDATE_REPAIR_WITHOUT_GATE_COUNTS
          } else {
            validJsonOutput(phaseId)
          },
        )
      },
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    harness.seedPhase("review", "completed", 1, phaseAgent("review"), VALID_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPhaseOrder().count { it == "validate" })
    assertTrue(harness.launchedPhaseOrder().contains("write_history"))
    assertEquals(2, gateCalls.get())
    val validateOutput = requireNotNull(
      harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"]?.outputArtifact,
    )
    assertContains(validateOutput, "gate_run_count")
    assertContains(validateOutput, "Validation satisfied by runtime-owned gate execution.")
  }

  @Test
  fun `gate repair with non-schema agent stdout still settles after gate re-verify`() {
    val gateCalls = java.util.concurrent.atomic.AtomicInteger(0)
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        validationGateRunner = failThenPassValidationGateRunner(gateCalls),
        validationGatePlatformManifests = listOf(kotlinPackWithValidationGate()),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        val phaseId = phaseIdFromPrompt(prompt)
        if (phaseId == "validate" && prompt.contains("Gate repair — prose only, no phase-output schema")) {
          facts("Fixed A.kt from the compiler console. Deliberately not a phase envelope.")
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    harness.seedPhase("review", "completed", 1, phaseAgent("review"), VALID_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(2, gateCalls.get())
    assertTrue(
      harness.io.database.rejectedDiagnostics().none { it.metadata.phaseId == "validate" },
      "gate repair must not charge phase-output-schema rejection",
    )
    assertTrue(
      harness.launcher.requests.any {
        it.skillRunRequest.promptOverride?.contains("Gate repair — prose only, no phase-output schema") == true
      },
    )
  }

  @Test
  fun `absent-gate validate without gate_run_count completes on first attempt`() {
    // Packs without validation_gate use agent-run validate. Agents are told not to invent
    // gate_run_count; the runtime must attest measured-absent counts before consumer projection.
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      // Empty manifests → ValidationGateResolution.Absent → agent-run fallback.
      runtimeConfig = RuntimeHarnessConfig(validationGatePlatformManifests = emptyList()),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(
          if (phaseId == "validate") {
            VALIDATE_REPAIR_WITHOUT_GATE_COUNTS
          } else {
            validJsonOutput(phaseId)
          },
        )
      },
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    harness.seedPhase("review", "completed", 1, phaseAgent("review"), VALID_OUTPUT)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchedPhaseOrder().count { it == "validate" })
    assertTrue(harness.launchedPhaseOrder().contains("write_history"))
    val validateRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["validate"])
    assertEquals(1, validateRecord.attemptCount)
    val validateOutput = requireNotNull(validateRecord.outputArtifact)
    assertContains(validateOutput, """"gate_run_count":0""")
    assertContains(validateOutput, """"gate_runs":[]""")
  }

  // --- Subtask 2: bounded cyclic phase executor (AC10) ---

  @Test
  fun `non-mutating declared cycle iterates to convergence on a satisfying verdict`() {
    // plan re-enters preplan once via the backward edge (verdict needs_fix), then converges
    // (verdict advance) and the run completes; the agent never bypasses the cap.
    var planLaunches = 0
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "plan") {
          planLaunches += 1
          facts(verdictPlanOutput(if (planLaunches == 1) "needs_fix" else "advance"))
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )

    val report = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    // preplan launched twice (initial + one re-entry); plan launched twice.
    assertEquals(2, harness.launchedPhaseOrder().count { it == "preplan" })
    assertEquals(2, planLaunches)
  }

  @Test
  fun `cap exhaustion blocks loudly with loop id iteration count and unresolved verdict`() {
    // plan always reports needs_fix, so the backward edge fires up to its cap and then blocks.
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") verdictPlanOutput("needs_fix") else validJsonOutput(phaseId))
      },
    )

    val report = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "plan-fix")
    assertContains(blocked.blockedReason, "needs_fix")
    assertContains(blocked.blockedReason, PLAN_FIX_CAP.toString())
    // A durable terminal blocked record carries the loop context.
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertEquals("plan-fix", planRecord.loopId)
    assertEquals(PLAN_FIX_CAP, planRecord.edgeIteration)
    assertContains(requireNotNull(planRecord.outputArtifact), "\"verdict\":\"needs_fix\"")
  }

  @Test
  fun `per-edge counters increment and persist distinct from attempt count`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") verdictPlanOutput("needs_fix") else validJsonOutput(phaseId))
      },
    )

    harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    // The per-edge LOOP_EDGE ledger entries carry the runtime-minted edge iterations 1..cap, which
    // are distinct from the re-entered phase's attempt_count.
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    assertEquals((1..PLAN_FIX_CAP).toList(), loopEdges.mapNotNull { it.edgeIteration })
    assertTrue(loopEdges.all { it.loopId == "plan-fix" })
    // The re-entered preplan record persists the latest edge context, distinct from attempt_count.
    val preplanRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["preplan"])
    assertEquals("plan-fix", preplanRecord.loopId)
    assertEquals(PLAN_FIX_CAP, preplanRecord.edgeIteration)
    assertNotEquals(
      preplanRecord.attemptCount,
      preplanRecord.edgeIteration,
      "the per-edge iteration must be tracked distinctly from attempt_count",
    )
  }

  @Test
  fun `resume mid-cycle lands on the correct phase and edge iteration`() {
    // A prior run fired the edge to (cap - 1) and crashed with preplan re-entered. On resume the
    // edge fires ONCE more, reaching the cap, then blocks loudly. The seeded edge iteration is
    // load-bearing: had resume reset the watermark to 0, the edge would fire `cap` more times before
    // blocking instead of exactly one, so this proves resume continued from the durable iteration.
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") verdictPlanOutput("needs_fix") else validJsonOutput(phaseId))
      },
    )
    harness.seedReentryPhase("preplan", "running", 1, phaseAgent("preplan"), null, "plan-fix", PLAN_FIX_CAP - 1)

    val report = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "plan-fix")
    assertContains(blocked.blockedReason, PLAN_FIX_CAP.toString())
    assertContains(blocked.blockedReason, "needs_fix")
    // The watermark continued from the durable (cap - 1): the resume minted exactly ONE new
    // backward-edge iteration (the cap), then blocked. Had it reset to 0, the resume would mint
    // iterations 1..cap (two more fires) before blocking, so this pins the resume to the right
    // edge iteration, not just the right phase.
    val resumeEdgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
      .mapNotNull { it.edgeIteration }
    assertEquals(listOf(PLAN_FIX_CAP), resumeEdgeIterations)
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertEquals(PLAN_FIX_CAP, planRecord.edgeIteration)
  }

  @Test
  fun `cap-exhausted edge re-blocks on resume without relaunching`() {
    // The re-entered preplan already burned the edge cap on a prior run; on resume the runtime
    // re-blocks before relaunching, mirroring the same-phase cap-burned-resume guard.
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(validJsonOutput(phaseId))
      },
    )
    harness.seedReentryPhase("preplan", "running", 2, phaseAgent("preplan"), null, "plan-fix", PLAN_FIX_CAP)

    val report = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("preplan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "plan-fix")
    assertContains(blocked.blockedReason, PLAN_FIX_CAP.toString())
    assertContains(blocked.blockedReason, "needs_fix")
    // The cap-exhausted re-entered phase must not relaunch its agent.
    assertTrue(harness.launchedPhaseOrder().none { it == "preplan" })
  }

  @Test
  fun `edge-free declaration behaves exactly as the current forward pipeline`() {
    // An override carrying only the production forward pipeline (no backward edges) drives the same
    // phase order as the default run.
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    val forwardOnly = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
      forwardPhaseIds = ALL_PHASES,
      loopOnlyPhaseIds = setOf("implement_fix", "build"),
      entryGates = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.entryGates,
    )

    val report = harness.runner.run(harness.request(forwardOnly))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(
      listOf(
        "preplan",
        "plan",
        "implement",
        "audit",
        "review",
        "verify_findings",
        "validate",
        "write_history",
        "commit_push",
        "pr",
      ),
      harness.launchOrder(),
    )
  }

  @Test
  fun `blocked run persists a durable terminal blocked record alongside the ledger entry`() {
    // F-002: blocking persists a terminal blocked per-phase record so blocked-ness survives even
    // if the append-only ledger BLOCKED entry is later pruned by the retention cap.
    val harness = runnerHarness(validator = ThrowingValidator(failPhases = setOf("write_history")))

    harness.runner.run(harness.request())

    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["write_history"])
    assertEquals("blocked", implementRecord.status)
    assertTrue(requireNotNull(implementRecord.blockedReason).isNotBlank())
    assertNull(implementRecord.finishedAt)
  }

  @Test
  fun `a resumed running attempt re-mints started_at so duration measures only the current run`() {
    // F-007: on resume the running transition mints a fresh started_at (and keeps first_started_at)
    // so duration_millis times only the current run, not the resume gap.
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("plan", "running", 1, phaseAgent("plan"), outputArtifact = null)
    val seeded = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    val originalStartedAt = seeded.startedAt

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    // started_at re-minted on the resumed running attempt; first_started_at preserves the original.
    assertTrue(planRecord.startedAt >= originalStartedAt)
    assertEquals(originalStartedAt, planRecord.firstStartedAt)
  }

  @Test
  fun `run advances the coarse workflow row to the active phase and completes it on the final phase`() {
    // F-008: the coarse workflow row tracks the run instead of pinning at the initial step, so the
    // generic workflow get/list/latest agrees with FeatureTaskRuntimeStatusService.
    val harness = runnerHarness()

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val row = requireNotNull(harness.repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID))
    assertEquals("completed", row.workflowStatus)
    assertEquals("pr", row.currentStepId)
  }

  @Test
  fun `a blocked run advances the coarse workflow row to blocked at the blocked phase`() {
    // F-008: a blocked run marks the row blocked at the blocked phase.
    val harness = runnerHarness(validator = ThrowingValidator(failPhases = setOf("write_history")))

    harness.runner.run(harness.request())

    val row = requireNotNull(harness.repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID))
    assertEquals("blocked", row.workflowStatus)
    assertEquals("write_history", row.currentStepId)
  }

  @Test
  fun `missing required upstream output blocks loudly without launching the phase`() {
    val harness = runnerHarness()
    // implement is recorded complete but its output artifact is absent (corrupt durable state), so
    // the first phase consuming it must loud-fail rather than launch on a missing upstream. Under
    // the audit-first order that phase is audit, and review is never reached at all.
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, outputArtifact = null)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "implement")
    assertContains(blocked.blockedReason, "blind")
    assertTrue(harness.launchOrder().none { it == "audit" })
    assertTrue(harness.launchOrder().none { it == "review" })
  }

  @Test
  fun `all upstreams satisfied produces no spurious missing-upstream block`() {
    val harness = runnerHarness()
    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
  }

  @Test
  fun `a killed child's expired-lease running row is reconciled at startup and resumes from its phase`() {
    // AC-001: a child process that died mid-run leaves the workflow row non-terminal with an expired
    // lease. The next startup reconciles it to the resumable pending state (HarnessDeadProcessSupervisor
    // confirms the dead process) and the run resumes from its recorded phase instead of blocking as
    // already-running.
    val harness = runnerHarness()
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.repository.seedWorkerOwnership(expiredCrashedOwnership())

    val rowBefore = requireNotNull(harness.repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID))
    assertEquals("running", rowBefore.workflowStatus)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertContains(
      requireNotNull(harness.repository.reconciledInterruptionReasons[WORKFLOW_ID]),
      "lease_expired",
    )
    // The resume continued from implement; the reconciled row was not re-launched from earlier phases.
    assertTrue(harness.launchOrder().none { it == "preplan" || it == "plan" || it == "implement" })
  }

  @Test
  fun `emits observability events and appends durable ledger read back through the store`() {
    val harness = runnerHarness()

    harness.runner.run(harness.request())

    val started = harness.events.filterIsInstance<FeatureTaskRuntimeRunEvent.PhaseStarted>().map { it.phaseId }
    val done = harness.events.filterIsInstance<FeatureTaskRuntimeRunEvent.PhaseCompleted>().map { it.phaseId }
    assertEquals(COMPLETED_PHASES_CLEAN_RUN, started)
    assertEquals(COMPLETED_PHASES_CLEAN_RUN, done)

    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)

    @Suppress("UNCHECKED_CAST")
    val ledger = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as List<Map<String, Any?>>
    val actions = ledger.map { it["action"] as String }
    assertContains(actions, "start")
    assertContains(actions, "complete")
    val sequences = ledger.map { (it["sequence_number"] as Number).toInt() }
    assertEquals(sequences.sorted(), sequences)

    @Suppress("UNCHECKED_CAST")
    val records = artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val planRecord = records["plan"] as Map<String, Any?>
    assertEquals("completed", planRecord["status"])
    assertTrue((planRecord["started_at"] as String).isNotBlank())
    assertTrue((planRecord["finished_at"] as String).isNotBlank())
  }

  @Test
  fun `blocked run appends a blocked ledger entry`() {
    val harness = runnerHarness(validator = ThrowingValidator(failPhases = setOf("write_history")))

    harness.runner.run(harness.request())

    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)

    @Suppress("UNCHECKED_CAST")
    val ledger = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as List<Map<String, Any?>>
    val blockedEntry = ledger.single { it["action"] == "blocked" }
    assertEquals("write_history", blockedEntry["phase_id"])
    assertTrue((blockedEntry["blocked_reason"] as String).isNotBlank())
  }

  @Test
  fun `review phase launch request carries readOnlyPhase true, mutating phases do not`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val requests = harness.launcher.requests
    assertNotNull(
      harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
      "runtime-owned review must still persist a completed phase record",
    )

    val mutatingPhaseRequests = requests.filter { request ->
      val prompt = request.skillRunRequest.promptOverride ?: return@filter false
      val phaseId = phaseIdFromPrompt(prompt)
      phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW &&
        phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
    }
    assertTrue(mutatingPhaseRequests.isNotEmpty(), "at least one mutating phase must have been launched")
    mutatingPhaseRequests.forEach { request ->
      assertFalse(
        request.skillRunRequest.readOnlyPhase,
        "phase '${request.skillRunRequest.promptOverride}' must not carry readOnlyPhase=true",
      )
    }
  }
}

// Runtime-owned lifecycle telemetry (started/finished/error) of the runner, split from
// FeatureTaskRuntimeRunnerTest so each class stays within its size budget while sharing the same
// file-private run harness.
class FeatureTaskRuntimeLifecycleTelemetryRunnerTest {
  @Test
  fun `runtime emits started on open and finished completed from its own per-phase records`() {
    val harness = telemetryRunnerHarness()

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.lifecycle.startedRecords.size)
    val started = harness.lifecycle.startedRecords.single()
    assertEquals("MEDIUM", started.featureSize)
    assertEquals(ISSUE_KEY, started.issueKey)
    assertEquals(1, harness.lifecycle.finishedRecords.size)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals(started.sessionId, finished.sessionId)
    assertEquals("completed", finished.completionStatus)
    assertEquals(COMPLETED_PHASES_CLEAN_RUN, finished.completedPhaseIds)
    assertEquals(COMPLETED_PHASES_CLEAN_RUN.associateWith { "completed" }, finished.phaseOutcomes)
    assertEquals("completed", finished.lastIncompletePhase)
    assertEquals("", finished.blockedReason)
  }

  @Test
  fun `runtime lifecycle telemetry honors run db override`() {
    val dbOverride = "/tmp/skillbill-runtime-override.db"
    val harness = telemetryRunnerHarness(runtimeConfig = RuntimeHarnessConfig(dbPathOverride = dbOverride))

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(harness.database.transactionDbOverrides.isNotEmpty())
    assertTrue(harness.database.transactionDbOverrides.all { it == dbOverride })
    assertEquals(SESSION_ID, harness.lifecycle.startedRecords.single().sessionId)
    assertEquals(SESSION_ID, harness.lifecycle.finishedRecords.single().sessionId)
  }

  @Test
  fun `runtime emits finished blocked with last incomplete phase from its own per-phase records`() {
    val harness = telemetryRunnerHarness(launcher = RuntimeRecordingLauncher { spawnFailedFacts() })

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals("blocked", finished.completionStatus)
    assertEquals("preplan", finished.lastIncompletePhase)
    assertTrue(finished.blockedReason.startsWith("runtime:"))
  }

  @Test
  fun `runtime emits finished decomposed at planning from its own per-phase records`() {
    // F-004: the only end-to-end coverage of completionStatusOf(Decomposed) -> decomposed_at_planning.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-telemetry-decompose")
    val harness = telemetryRunnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Decomposed>(report)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals("decomposed_at_planning", finished.completionStatus)
    assertEquals(listOf("preplan", "plan"), finished.completedPhaseIds)
    assertEquals("decomposed_at_planning", finished.lastIncompletePhase)
    assertEquals("", finished.blockedReason)
  }

  @Test
  fun `runtime emits finished error and rethrows when an exception escapes the run loop`() {
    // F-002/F-004: an exception escaping the loop must close the started session with the error
    // completion bucket (failure-isolated) while the original exception still propagates.
    val boom = RuntimeException("launcher exploded")
    val harness = telemetryRunnerHarness(launcher = RuntimeRecordingLauncher { throw boom })

    val thrown = assertFailsWith<RuntimeException> { harness.runner.run(harness.request) }

    assertEquals(boom, thrown)
    assertEquals(1, harness.lifecycle.startedRecords.size)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals("error", finished.completionStatus)
    assertEquals(harness.lifecycle.startedRecords.single().sessionId, finished.sessionId)
    assertEquals("preplan", finished.lastIncompletePhase)
    assertTrue(finished.blockedReason.startsWith("runtime:"))
  }

  @Test
  fun `runtime finished error emits even when phase outcome loading fails`() {
    val lifecycle = RecordingLifecycleTelemetryRepository()
    val database = RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), lifecycle)
    val telemetry = FeatureTaskRuntimeLifecycleTelemetry(
      LifecycleTelemetryService(database, EnabledRuntimeTelemetrySettingsProvider),
    )

    telemetry.finishedError(
      SESSION_ID,
      phaseOutcomes = { error("phase load failed") },
      reviewFixIterationCount = { 0 },
      auditGapIterationCount = { 0 },
      dbOverride = null,
    )

    val finished = lifecycle.finishedRecords.single()
    assertEquals(SESSION_ID, finished.sessionId)
    assertEquals("error", finished.completionStatus)
    assertEquals(emptyMap(), finished.phaseOutcomes)
    assertEquals(emptyList(), finished.completedPhaseIds)
    assertEquals("unknown", finished.lastIncompletePhase)
    assertTrue(finished.blockedReason.startsWith("runtime:"))
  }
}

// The goal-subtask remediation loop's durable review generation: SKILL-157 retired the two-pass
// ceiling, so no verdict is ever settled by cap exhaustion and there is no capped generation to
// reopen. What survives is the loop terminating on the Blocker clearing, each pass dispositioning its
// immediately preceding pass, and the reviewed-delta digest recording what the settled pass judged.
// Split from FeatureTaskRuntimeRunnerPersistenceTest so each class stays within its size budget while
// sharing the same file-private run harness.
class FeatureTaskRuntimeRemediationGenerationTest {
  // AC-002/AC-005/AC-006: three passes, each dispositioning the one before it, converge on the pass
  // that clears the Blocker — no cap settles the verdict and no pause is minted along the way.
  @Test
  fun `one review pass with implement_fix advances without reserving another pass`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-remediation-converge")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(
      repoRoot,
      git,
      remediationReviewLauncher(git),
      reviewDriver = reviewFixDriver(2),
    )

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchOrder().count { it == "review" })
    val reviewState = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, reviewState.completedPassCount)
    assertEquals(listOf(1), reviewState.passResults.map { it.passNumber })
    assertFalse(reviewState.reviewCapReached)
    assertFalse(reviewState.pausedForOperatorDecision)
  }

  @Test
  fun `unresolved findings after one fix round still advance without pausing`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-remediation-nonconvergence")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(
      repoRoot,
      git,
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement_fix" && git.goalReviewTrackedDelta.isEmpty()) {
          git.goalReviewTrackedDelta = "remediation-progress\n"
        }
        facts(validJsonOutput(phaseId))
      },
      reviewDriver = reviewFixDriver(2),
    )

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchOrder().count { it == "review" })
    assertEquals(1, harness.launchOrder().count { it == "implement_fix" })
    val reviewState = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertFalse(reviewState.pausedForOperatorDecision)
    assertEquals(1, reviewState.completedPassCount)
  }

  // AC-006 / task-5: a healthy remediation round produces one remediation checkpoint whose sha is
  // exactly the stored remediation_base_sha, and writes no recovery evidence.
  @Test
  fun `normal remediation round records base equal to the checkpoint commit with no recovery evidence`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-remediation-parity")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(
      repoRoot,
      git,
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement" || phaseId == "implement_fix") {
          git.worktreeStatusValue = " M src/Foo.kt"
          git.ownedPathsValue = listOf("src/Foo.kt")
        }
        facts(validJsonOutput(phaseId))
      },
      reviewDriver = reviewFixDriver(2),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE)),
    )

    assertEquals(1, git.createCommitMessages.size, "one subtask commit on the branch")
    val remediationMessages = git.amendCommitMessages.filter { it.contains("remediation checkpoint") }
    assertEquals(1, remediationMessages.size, "exactly one remediation checkpoint on a healthy round")
    val remediationIndex = git.amendCommitMessages.indexOf(remediationMessages.single())
    val remediationSha = "a${(remediationIndex + 1).toString(16)}".padStart(40, '0')
    val reviewState = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(remediationSha, reviewState.remediationBaseSha, "recorded base must equal the checkpoint tip")
    // Soft-reset must not fire on the happy path; recovery evidence must stay absent.
    assertTrue(git.resetSoftToCommitCalls.isEmpty())
    assertNull(harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_review_base_recoveries"])
  }

  // AC-001/AC-005 / task-4: after the orphaning sequence (base recorded, then branch tip replaced by a
  // sibling), resume coherence leaves the durable row and the git ref in agreement. Documented to
  // fail against a pre-fix runtime that lacks reconcileRemediationBaseCoherence.
  @Test
  fun `orphaning sequence then resume leaves branch tip and remediation_base_sha in agreement`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-orphan-sequence")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(
      repoRoot,
      git,
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement" || phaseId == "implement_fix") {
          git.worktreeStatusValue = " M src/Foo.kt"
          git.ownedPathsValue = listOf("src/Foo.kt")
        }
        facts(validJsonOutput(phaseId))
      },
      reviewDriver = reviewFixDriver(2),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE)),
    )
    val recordedBase = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID)?.remediationBaseSha)
    // Reproduce the SKILL-15 sibling rewrite outside commitCheckpoint: tip moves to a new sha that
    // does not contain the recorded base, without updating the durable row.
    val siblingTip = "b".repeat(40)
    git.headCommitShaValue = siblingTip
    git.nonAncestorPairs += recordedBase to siblingTip
    assertEquals(
      "false",
      git.isCommitAncestor(repoRoot, recordedBase, siblingTip).value,
      "pre-fix: recorded base must be unreachable from the rewritten tip",
    )
    assertEquals(recordedBase, harness.goalContinuationRecorder.reviewState(WORKFLOW_ID)?.remediationBaseSha)

    val healed = assertIs<RemediationBaseCoherent>(
      harness.goalContinuationRecorder.reconcileRemediationBaseCoherence(WORKFLOW_ID, git, repoRoot),
    )
    val reconciledBase = requireNotNull(healed.state?.remediationBaseSha)
    assertEquals(reconciledBase, harness.goalContinuationRecorder.reviewState(WORKFLOW_ID)?.remediationBaseSha)
    assertNotEquals(siblingTip, reconciledBase)
    assertEquals("false", git.isCommitAncestor(repoRoot, recordedBase, siblingTip).value)
    @Suppress("UNCHECKED_CAST")
    val recoveries = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_review_base_recoveries"]
      as List<Map<String, Any?>>?
    recoveries?.forEach { entry -> assertEquals(reconciledBase, entry["replacement_sha"]) }
  }

  // A settled subtask's review is replayed from its durable result on resume rather than relaunched,
  // so a resume neither re-reviews converged work nor allocates another pass.
  @Test
  fun `a resumed settled subtask replays its review without relaunching it or reserving another pass`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-remediation-resume")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(
      repoRoot,
      git,
      remediationReviewLauncher(git),
      reviewDriver = reviewFixDriver(3),
    )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE)),
    )
    val settledLaunches = harness.launchOrder().count { it == "review" }
    val settledPassCount = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID)).completedPassCount

    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE)),
    )

    assertEquals(
      settledLaunches,
      harness.launchOrder().count { it == "review" },
      "a settled review is replayed, never relaunched",
    )
    assertEquals(
      settledPassCount,
      requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID)).completedPassCount,
      "the resume allocates no further pass",
    )
  }
}

// Persistence, resume, decompose, and observability behaviour of the runner, split from
// FeatureTaskRuntimeRunnerTest so each class stays within its size budget while sharing the same
// file-private run harness.
class FeatureTaskRuntimeRunnerPersistenceTest {
  @Test
  fun `persists per-phase briefing durably with run-invariants upstream and review diff`() {
    val harness = runnerHarness()

    harness.runner.run(harness.request())

    val briefings = harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()
    assertEquals(COMPLETED_PHASES_CLEAN_RUN.toSet(), briefings.keys)

    briefings.forEach { (phaseId, briefing) ->
      assertEquals(SPEC_REFERENCE, briefing.specReference, "spec reference for $phaseId")
      assertEquals("MEDIUM", briefing.featureSize, "feature size for $phaseId")
      assertEquals(listOf("AC-1", "AC-2"), briefing.acceptanceCriteria, "criteria for $phaseId")
      assertEquals(listOf("mandate-X"), briefing.mandatesAndOverrides, "mandates for $phaseId")
      assertContains(briefing.briefingText, "feature_size: MEDIUM")
      assertContains(briefing.briefingText, SPEC_REFERENCE)
      // The typed mandates field is durable state on every briefing (asserted above); only the
      // rendered prompt narrows, per the per-phase run-invariant allowlist.
      if (phaseId !in FINALIZATION_PHASE_IDS) {
        assertContains(briefing.briefingText, "mandate-X")
      }
    }
    // plan and implement receive bounded planning projections rather than coarse upstream receipts.
    assertContains(briefings.getValue("plan").briefingText, "affected_boundaries")
    assertContains(briefings.getValue("implement").briefingText, "Fixture task.")
    assertContains(briefings.getValue("review").briefingText, "clearance_status: satisfied")
    assertFalse(briefings.getValue("review").briefingText.contains(validJsonOutput("implement")))
    assertEquals(listOf("diff"), briefings.getValue("review").derivedContextKeys)
    assertContains(briefings.getValue("review").briefingText, "diff")
  }

  private fun normalizedOutput(output: String): Map<String, Any?> =
    skillbill.contracts.JsonSupport.parseObjectOrNull(output)
      ?.let(skillbill.contracts.JsonSupport::jsonElementToValue)
      ?.let(skillbill.contracts.JsonSupport::anyToStringAnyMap)
      ?: error("Expected JSON object output.")

  @Test
  fun `launch spawn failure blocks distinctly without schema gate or fix loop retries`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { spawnFailedFacts() },
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("preplan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "failed to launch")
    assertTrue(!blocked.blockedReason.contains("schema"))
    assertTrue(!blocked.blockedReason.contains("exhausted the bounded fix loop"))
    assertEquals(listOf("preplan"), harness.launchedPhaseOrder())
  }

  @Test
  fun `launch timeout on a fix-loop phase blocks distinctly without burning the budget`() {
    val harness = runnerHarness(
      runtimeConfig = RuntimeHarnessConfig(
        reviewDriver = failingReviewDriver(
          failOnPass = 1,
          failureReason = "launch timed out before the agent produced an output.",
        ),
      ),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "timed out")
    assertTrue(!blocked.blockedReason.contains("exhausted the bounded fix loop"))
    assertEquals(1, harness.launchOrder().count { it == "review" })
  }

  @Test
  fun `a review driver parent-packet budget overflow blocks instead of crashing`() {
    val harness = runnerHarness(
      runtimeConfig = RuntimeHarnessConfig(
        reviewDriver = throwingBudgetReviewDriver(),
      ),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "review_context_budget_exceeded")
    assertContains(blocked.blockedReason, "parent_packet_bytes")
  }

  @Test
  fun `malformed per-phase records artifact loud-fails on resume`() {
    val harness = runnerHarness()
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.repository.corruptRecordsArtifact(WORKFLOW_ID, "not-a-map")

    val failure = assertFailsWith<InvalidWorkflowStateSchemaError> {
      harness.runner.run(harness.request())
    }
    assertContains(failure.message.orEmpty(), FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY)
  }

  @Test
  fun `each phase launch delivers the briefing and output contract as the prompt override`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    harness.runner.run(harness.request())

    assertEquals(AGENT_LAUNCHED_PHASES.size, harness.launcher.requests.size)
    harness.launcher.requests.zip(AGENT_LAUNCHED_PHASES).forEach { (request, phaseId) ->
      val prompt = requireNotNull(request.skillRunRequest.promptOverride) {
        "phase '$phaseId' must launch with a prompt override, not the goal-continuation default"
      }
      assertContains(prompt, "# Feature-task-runtime phase briefing")
      assertContains(prompt, "phase: $phaseId")
      assertContains(prompt, "feature_size: MEDIUM")
      assertContains(prompt, "Scaling changes scope and verbosity only")
      assertContains(prompt, SPEC_REFERENCE)
      if (phaseId !in FINALIZATION_PHASE_IDS) {
        assertContains(prompt, "mandate-X")
      }
      assertContains(prompt, "Required final output")
      assertContains(prompt, "\"phase_id\": must be \"$phaseId\"")
      assertContains(
        prompt,
        "\"contract_version\": must be exactly " +
          "\"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION\"",
      )
      assertTrue(
        !prompt.contains("goal-continuation mode") && !prompt.contains("First execute this exact command"),
        "phase prompt for '$phaseId' must not instruct the goal-continuation flow",
      )
    }
  }

  @Test
  fun `resume preserves durable feature size instead of re-resolving from changed request inputs`() {
    val harness = runnerHarness(
      runtimeConfig = smallRuntimeConfig(),
    )
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val changedRequest = harness.request().copy(
      runInvariants = harness.request().runInvariants.copy(featureSize = FeatureTaskRuntimeFeatureSize.LARGE),
    )
    val resumed = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(changedRequest))

    assertEquals("SMALL", resumed.featureSize)
    val projection = requireNotNull(
      FeatureTaskRuntimeStatusService(
        harness.recorder,
        harness.runInvariantsStore,
        harness.decomposeTerminalRecorder,
      )
        .status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID)),
    )
    assertEquals("SMALL", projection.featureSize)
  }

  @Test
  fun `partial resume launches review with durable small size and current unit scope`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = smallRuntimeConfig(),
    )
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.runInvariantsStore.resolve(WORKFLOW_ID, proposed = harness.request().runInvariants)

    val changedRequest = harness.request().copy(
      runInvariants = harness.request().runInvariants.copy(featureSize = FeatureTaskRuntimeFeatureSize.LARGE),
    )
    val resumed = assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(changedRequest))

    assertEquals("SMALL", resumed.featureSize)
    assertEquals(
      listOf("audit", "verify_findings", "validate", "write_history", "commit_push", "pr"),
      harness.launchedPhaseOrder(),
    )
    val reviewBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["review"])
    assertContains(reviewBriefing.briefingText, "feature_size: SMALL")
    assertContains(reviewBriefing.briefingText, "review_scope: current_unit_of_work")
    assertContains(reviewBriefing.briefingText, "current-unit-of-work review scope")
    assertEquals("SMALL", reviewBriefing.featureSize)
    assertEquals(listOf("current_unit_of_work"), reviewBriefing.derivedContextKeys)
  }

  @Test
  fun `a terminal schema-gate block persists the validator's reason in the blocked reason`() {
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("write_history")),
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertPrivateDiagnosticRejection(blocked.blockedReason, "phase-output-schema", "rejected by fake validator")
    val writeHistoryRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["write_history"])
    assertPrivateDiagnosticRejection(
      requireNotNull(writeHistoryRecord.blockedReason),
      "phase-output-schema",
      "rejected by fake validator",
    )
  }

  @Test
  fun `per-phase records carry runtime-owned timestamps agent id and status`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()
    assertEquals(COMPLETED_PHASES_CLEAN_RUN.toSet(), records.keys)
    COMPLETED_PHASES_CLEAN_RUN.forEach { phaseId ->
      val record = records.getValue(phaseId)
      assertEquals("completed", record.status, "status for $phaseId")
      assertTrue(record.attemptCount >= 1, "attempt count for $phaseId")
      assertTrue(record.startedAt.isNotBlank(), "startedAt for $phaseId")
      assertTrue(requireNotNull(record.finishedAt).isNotBlank(), "finishedAt for $phaseId")
      assertTrue(requireNotNull(record.durationMillis) >= 0, "durationMillis for $phaseId")
      assertEquals(phaseAgent(phaseId), record.resolvedAgentId, "resolved agent id for $phaseId")
    }
  }

  @Test
  fun `accepted repaired output persists canonical payload and typed evidence`() {
    val harness = runnerHarness(
      validator = RepairingImplementOutputValidator,
      agentAssignment = phasePerAgentAssignment(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val implement = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals(validJsonOutput("implement"), implement.outputArtifact)
    assertEquals("implement", implement.repairEvidence?.sourceLocation?.sourceLabel)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
      implement.repairEvidence?.operation,
    )
    assertFalse(implement.toArtifactMap().toString().contains("original_payload"))
  }

  @Test
  fun `throwing DecomposedAtPlanning event sink does not alter decompose completion`() {
    // Realistic bug: terminal persistence succeeds, then a status/telemetry observer throws on
    // DecomposedAtPlanning and aborts an otherwise completed decompose stop (AC-010 / F-001).
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-throwing-sink")
    val diagnostics = RecordingDiagnostics()
    val throwingSink = FeatureTaskRuntimeRunEventSink { event ->
      if (event is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning) {
        error("status/telemetry observer refused DecomposedAtPlanning")
      }
    }
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = repoRoot,
        useRealDecompositionPlanner = true,
        eventSink = throwingSink,
      ),
      diagnostics = diagnostics,
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Decomposed>(report)
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    assertTrue(
      diagnostics.warnings.any { it.contains("DecomposedAtPlanning event-sink emission failed") },
      "observer failure must leave an independent payload-free diagnostic record",
    )
  }

  @Test
  fun `decompose plan writes shared feature specs records terminal completed status and skips implement`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )

    val report = harness.runner.run(harness.request())

    val decomposed = assertIs<FeatureTaskRuntimeRunReport.Decomposed>(report)
    assertEquals(listOf("preplan", "plan"), decomposed.completedPhaseIds)
    assertEquals(2, decomposed.subtaskSpecPaths.size)
    assertEquals(listOf("preplan", "plan"), harness.launchedPhaseOrder())
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    // AC3: a spec.md parent and ordered spec_subtask_1*.md then spec_subtask_2*.md.
    assertTrue(
      decomposed.parentSpecPath.endsWith("spec.md"),
      "parent spec path must end with spec.md: ${decomposed.parentSpecPath}",
    )
    val firstSubtaskName = Path.of(decomposed.subtaskSpecPaths[0]).fileName.toString()
    val secondSubtaskName = Path.of(decomposed.subtaskSpecPaths[1]).fileName.toString()
    assertTrue(
      firstSubtaskName.startsWith("spec_subtask_1") && firstSubtaskName.endsWith(".md"),
      "first subtask spec must be spec_subtask_1*.md: $firstSubtaskName",
    )
    assertTrue(
      secondSubtaskName.startsWith("spec_subtask_2") && secondSubtaskName.endsWith(".md"),
      "second subtask spec must be spec_subtask_2*.md: $secondSubtaskName",
    )
    assertTrue(Files.isRegularFile(repoRoot.resolve(decomposed.parentSpecPath)))
    assertTrue(Files.isRegularFile(repoRoot.resolve(decomposed.decompositionManifestPath)))
    decomposed.subtaskSpecPaths.forEach { path -> assertTrue(Files.isRegularFile(repoRoot.resolve(path))) }

    val row = requireNotNull(harness.repository.getFeatureTaskRuntimeWorkflow(WORKFLOW_ID))
    assertEquals("completed", row.workflowStatus)
    assertEquals("plan", row.currentStepId)
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)
    assertTrue(artifacts.containsKey(FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY))
    val terminal = requireNotNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
    assertEquals(decomposed.decompositionManifestPath, terminal.decompositionManifestPath)
    assertContains(terminal.reason, "needs ordered subtasks")
  }

  @Test
  fun `decompose plan uses resolved artifact source instead of agent-authored source`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-linear-decompose")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\nspec_source: linear\n---\n\n# Spec\n")
    val linearPlanOutput = DECOMPOSE_PLAN_OUTPUT
      .replace("\"subtasks\": [", "\"spec_source\": \"local\",\n      \"subtasks\": [")
      .replace("\"depends_on\": []", "\"linear_issue_id\": \"linear-subtask-1\",\n          \"depends_on\": []")
      .replace("\"depends_on\": [1]", "\"linear_issue_id\": \"linear-subtask-2\",\n          \"depends_on\": [1]")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") linearPlanOutput else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )

    val report = assertIs<FeatureTaskRuntimeRunReport.Decomposed>(harness.runner.run(harness.request()))
    val manifest = loadDecompositionManifest(repoRoot.resolve(report.decompositionManifestPath))

    assertEquals(SpecSource.LINEAR, manifest.specSource)
    assertEquals(listOf("linear-subtask-1", "linear-subtask-2"), manifest.subtasks.map { it.linearIssueId })
  }
}

// Goal-continuation persistence and SHA measurement paths. Kept outside
// [FeatureTaskRuntimeRunnerPersistenceTest] so that suite stays under the detekt LargeClass threshold.
@Suppress("LargeClass")
class FeatureTaskRuntimeGoalContinuationPersistenceTest {
  @Test
  fun `goal-continuation run suppresses decompose and pr then completes at commit push`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-subtask")
    val harness = runnerHarness(
      // A goal child plans in direct mode: decomposition is not a valid terminal outcome for it, and
      // AC-015 keeps decomposition data out of the implementation projection entirely.
      launcher = RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        val phaseId = phaseIdFromPrompt(prompt)
        facts(validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = repoRoot,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
        useRealDecompositionPlanner = true,
      ),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(AGENT_LAUNCHED_PHASES.filterNot { it == "pr" }, harness.launchedPhaseOrder())
    assertEquals("commit_push", completed.subtaskOutcome?.lastResumableStep)
    assertNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)
    assertEquals(
      mapOf(
        "issue_key" to ISSUE_KEY,
        "subtask_id" to 5,
        "suppress_pr" to true,
        "goal_branch" to "feat/existing-runtime-branch",
        "code_review_mode" to "inline",
        "validation_depth" to "full",
        "parent_workflow_id" to "wfl-parent",
        "quality_gate_selection" to "validate",
      ),
      artifacts["goal_continuation"],
    )
    val outcome = artifacts["goal_continuation_outcome"] as Map<*, *>
    assertEquals("complete", outcome["status"])
    assertEquals(completed.subtaskOutcome?.commitSha, outcome["commit_sha"])
    assertTrue(outcome["commit_sha"].toString().isNotBlank(), "the runtime-captured finalisation sha is recorded")
    assertEquals("commit_push", outcome["last_resumable_step"])
    assertEquals(phaseAgent("commit_push"), outcome["finalizing_agent_id"])
    @Suppress("UNCHECKED_CAST")
    val participants = outcome["participating_agent_ids"] as List<String>
    assertTrue(participants.isNotEmpty(), "goal-continuation outcome must carry a non-empty participating_agent_ids")
    val installSync = artifacts["install_sync_result"] as Map<*, *>
    assertEquals("deferred", installSync["status"])
    assertContains(installSync["reason"].toString(), "must not block subtask completion")
  }

  // SKILL-190 AC-009/AC-011: the recorded sha is the one the runtime captured after its own finalisation
  // commit, and the goal-continuation outcome carries that same value rather than a second reading.
  @Test
  fun `goal-continuation records the runtime-captured finalisation sha`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-payload-sha")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals("complete", completed.subtaskOutcome?.status)
    assertEquals(git.headCommitShaValue, completed.subtaskOutcome?.commitSha)
    assertEquals(listOf("feat/existing-runtime-branch"), git.pushedBranches, "finalisation pushes exactly once")
    val outcome = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_continuation_outcome"] as Map<*, *>
    assertEquals("complete", outcome["status"])
    assertEquals(completed.subtaskOutcome?.commitSha, outcome["commit_sha"])
  }

  @Test
  fun `goal review preserves a top-level changes requested verdict without findings`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = committedRepoBranchSetup(),
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
        reviewDriver = reviewFixDriver(2),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    @Suppress("UNCHECKED_CAST")
    val state = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_subtask_review_state"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val firstPass = (state["pass_results"] as List<Map<String, Any?>>).first()
    assertEquals("changes_requested", firstPass["verdict"])
    assertEquals(1, firstPass["unresolved_finding_count"])
  }

  @Test
  fun `orphaned goal review state blocks before a runtime child can be treated as standalone`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    val preplanOnly = harness.request().copy(
      transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
        forwardPhaseIds = listOf("preplan"),
        backwardEdges = emptyList(),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(preplanOnly))
    val orphanedArtifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap().apply {
      remove("goal_continuation")
    }
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, orphanedArtifacts)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(preplanOnly))
    assertContains(blocked.blockedReason, "Goal-continuation review persistence is malformed")
    assertEquals(1, harness.launcher.requests.size)
  }

  @Test
  fun `goal review runs implement_fix and resumes after a crash without a second review pass`() {
    var crashOnFix = true
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = committedRepoBranchSetup(),
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
        reviewDriver = reviewFixDriver(2),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement_fix" && crashOnFix) spawnFailedFacts() else facts(validJsonOutput(phaseId))
      },
    )

    val first = assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      harness.runner.run(
        harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
      ),
    )
    assertEquals("implement_fix", first.lastIncompletePhase)
    val reserved = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, reserved.completedPassCount)
    assertEquals(null, reserved.reservedPassNumber)

    crashOnFix = false
    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(
        harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
      ),
    )
    val resumed = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, resumed.completedPassCount)
    assertEquals(null, resumed.reservedPassNumber)
    assertEquals(listOf(1), resumed.passResults.map { it.passNumber })
    assertEquals(
      listOf(CodeReviewExecutionMode.INLINE),
      resumed.passResults.map { it.executedMode },
    )
    assertEquals(1, harness.launchOrder().count { it == "review" })
    assertEquals(2, harness.launchOrder().count { it == "implement_fix" })
  }

  @Test
  fun `goal review recovers an incompatible baseline before review evidence exists`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-review-recover")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val recoveredBaseline = GoalSubtaskReviewBaseline("1".repeat(40), listOf("preexisting.tmp"))
    git.goalReviewBuildResults += GoalSubtaskReviewInputResult(
      status = "error",
      error = "Persisted review base '${"0".repeat(40)}' is not an ancestor of current HEAD.",
      failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
    )
    git.goalReviewRecoveredBaseline = recoveredBaseline
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    val state = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(recoveredBaseline.reviewBaseSha, state.reviewBaseSha)
    assertEquals(recoveredBaseline.baselineUntrackedPaths, state.baselineUntrackedPaths)
    assertEquals(1, git.goalReviewRecoverCalls)
    assertEquals(
      listOf("0".repeat(40), recoveredBaseline.reviewBaseSha),
      git.goalReviewBuildInputs.map { it.reviewBaseSha },
    )
  }

  @Test
  @Suppress("LongMethod") // end-to-end review prep harness; splitting would obscure AC-009/AC-012 wiring
  fun `non-scope failure inside review preparation surfaces its own cause not the fixed scope sentence`() {
    // Bug this catches: the review dispatch substituted a fixed scope sentence after
    // blockedGoalReviewRun already persisted the specific reservation/evidence-store cause (AC-009/AC-012).
    val evidenceStoreCause = "[evidence-store] retaining producer-output evidence for review:0:2 failed"
    val fixedScopeSentence =
      "Goal-subtask review preparation could not establish the exact durable review scope."
    val repoRoot = Files.createTempDirectory("skillbill-runtime-review-prep-nonscope")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    check(
      harness.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = WORKFLOW_ID,
          continuation = FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = ISSUE_KEY,
            subtaskId = 5,
            suppressPr = true,
            goalBranch = "feat/existing-runtime-branch",
            parentWorkflowId = "wfl-parent",
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ),
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    harness.repository.failSaveWhen = { row ->
      val artifacts = skillbill.contracts.JsonSupport.parseObjectOrNull(row.artifactsJson)
        ?.let(skillbill.contracts.JsonSupport::jsonElementToValue)
        ?.let(skillbill.contracts.JsonSupport::anyToStringAnyMap)
        .orEmpty()
      val reserved = (artifacts["goal_subtask_review_state"] as? Map<*, *>)?.get("reserved_pass_number")
      if (reserved != null) {
        throw IllegalStateException(evidenceStoreCause)
      }
      false
    }

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      harness.runner.run(
        harness.request().copy(
          transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
            forwardPhaseIds = listOf("preplan", "plan", "implement", "audit", "review"),
            backwardEdges = emptyList(),
          ),
        ),
      ),
    )

    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, evidenceStoreCause)
    assertContains(blocked.blockedReason, "Goal-subtask review reservation failed")
    assertTrue(
      fixedScopeSentence !in blocked.blockedReason,
      "fixed scope sentence must not replace the injected non-scope cause",
    )
    val phaseBlocked = harness.events.filterIsInstance<FeatureTaskRuntimeRunEvent.PhaseBlocked>()
      .single { it.phaseId == "review" }
    assertContains(phaseBlocked.blockedReason, evidenceStoreCause)
    assertTrue(fixedScopeSentence !in phaseBlocked.blockedReason)
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertContains(requireNotNull(reviewRecord.blockedReason), evidenceStoreCause)
  }

  @Test
  fun `scope-shaped review preparation failure still reports the scope-specific message`() {
    // Bug this catches: removing the fixed scope sentence must not lose genuine scope failures (AC-010).
    val repoRoot = Files.createTempDirectory("skillbill-runtime-review-prep-scope")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    check(
      harness.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = WORKFLOW_ID,
          continuation = FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = ISSUE_KEY,
            subtaskId = 5,
            suppressPr = true,
            goalBranch = "feat/existing-runtime-branch",
            parentWorkflowId = "wfl-parent",
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ),
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    artifacts.remove("goal_subtask_review_state")
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      harness.runner.run(
        harness.request().copy(
          transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
            forwardPhaseIds = listOf("preplan", "plan", "implement", "audit", "review"),
            backwardEdges = emptyList(),
          ),
        ),
      ),
    )

    println("DEBUG blockedReason=" + blocked.blockedReason)
    println("DEBUG lastIncompletePhase=" + blocked.lastIncompletePhase)
    assertEquals("review", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "review_base_sha must be captured before implementation")
    assertTrue(
      "Goal-subtask review preparation could not establish the exact durable review scope." !in
        blocked.blockedReason,
    )
  }

  @Test
  fun `AC-008 unreachable remediation base with completed passes recovers through review preparation`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-ac008-remediation-recover")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val unreachableRemediation = "7".repeat(40)
    val recoveredRemediation = "8".repeat(40)
    git.goalReviewBuildResults += GoalSubtaskReviewInputResult(
      status = "error",
      error = "Persisted review base '$unreachableRemediation' is not an ancestor of current HEAD.",
      failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
    )
    git.goalReviewRecoveredBaseline = GoalSubtaskReviewBaseline(recoveredRemediation, emptyList())
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    check(
      harness.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = WORKFLOW_ID,
          continuation = FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = ISSUE_KEY,
            subtaskId = 5,
            suppressPr = true,
            goalBranch = "feat/existing-runtime-branch",
            parentWorkflowId = "wfl-parent",
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ),
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    // Seed one completed pass with an orphaned remediation base that is no longer an ancestor.
    var state = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    state = state.reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = emptyList(),
    ).copy(remediationBaseSha = unreachableRemediation)
    checkNotNull(harness.goalContinuationRecorder.updateReviewState(WORKFLOW_ID) { state })
    harness.seedRawReviewResults(state)

    // Drive review preparation the run loop uses — the pre-fix gate refused this shape.
    val prepared = harness.goalContinuationRecorder.buildGoalReviewInput(WORKFLOW_ID, git, repoRoot)

    assertIs<GoalSubtaskReviewInputReady>(prepared)
    assertEquals(recoveredRemediation, harness.goalContinuationRecorder.reviewState(WORKFLOW_ID)?.remediationBaseSha)
    assertEquals(1, git.goalReviewRecoverCalls)
    assertEquals(unreachableRemediation, git.goalReviewRecoverRequests.single().unreachableSha)
    val evidence = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["goal_review_base_recoveries"] as List<*>
    val entry = evidence.single() as Map<*, *>
    assertEquals(unreachableRemediation, entry["original_sha"])
    assertEquals(recoveredRemediation, entry["replacement_sha"])
    assertEquals("base_not_ancestor", entry["failure_reason"])
  }

  @Test
  fun `non-recoverable review input failures do not enter baseline recovery`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-nonrecoverable-review-base")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    git.goalReviewBuildResults += GoalSubtaskReviewInputResult(
      status = "error",
      error = "Goal-subtask review must run on durable child branch 'feat/existing-runtime-branch'.",
      failureReason = null,
    )
    git.goalReviewRecoveredBaseline = GoalSubtaskReviewBaseline("1".repeat(40), emptyList())
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    check(
      harness.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = WORKFLOW_ID,
          continuation = FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = ISSUE_KEY,
            subtaskId = 5,
            suppressPr = true,
            goalBranch = "feat/existing-runtime-branch",
            parentWorkflowId = "wfl-parent",
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ),
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )

    val prepared = harness.goalContinuationRecorder.buildGoalReviewInput(WORKFLOW_ID, git, repoRoot)

    assertIs<GoalSubtaskReviewInputBlocked>(prepared)
    assertEquals(0, git.goalReviewRecoverCalls)
  }

  @Test
  @Suppress("LongMethod") // capped-review staleness harness with unreachable remediation base
  fun `cappedReviewIsStale ignores an unreachable remediation base and keeps an unchanged immutable digest`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-stale-unreachable-remediation")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val immutableDigest = GoalSubtaskReviewInput(
      reviewBaseSha = "0".repeat(40),
      currentHeadSha = COMMITTED_HEAD_SHA,
      trackedDelta = "immutable-delta\n",
      ownedUntrackedPatches = "",
    ).deltaDigest
    // First build call may be remediation (unreachable) or review base — queue both shapes.
    git.goalReviewBuildResults += GoalSubtaskReviewInputResult(
      status = "error",
      error = "Persisted review base '${"9".repeat(40)}' is not an ancestor of current HEAD.",
      failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
    )
    // Default success path uses baseline.reviewBaseSha; for the immutable base the fake returns empty
    // delta unless we pre-queue. Queue a matching digest for the immutable base probe.
    git.goalReviewBuildResults += GoalSubtaskReviewInputResult(
      status = "ok",
      input = GoalSubtaskReviewInput(
        reviewBaseSha = "0".repeat(40),
        currentHeadSha = COMMITTED_HEAD_SHA,
        trackedDelta = "immutable-delta\n",
        ownedUntrackedPatches = "",
      ),
    )
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    check(
      harness.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = WORKFLOW_ID,
          continuation = FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = ISSUE_KEY,
            subtaskId = 5,
            suppressPr = true,
            goalBranch = "feat/existing-runtime-branch",
            parentWorkflowId = "wfl-parent",
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ),
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    harness.seedReviewPhase("completed", 1, validJsonOutput("review"), reviewPassNumber = 1)
    val paused = GoalSubtaskReviewState.initial(
      reviewBaseSha = "0".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(
        GoalSubtaskReviewCompactFinding(
          severity = "blocker",
          label = "StaleCap",
          text = "unresolved",
          findingId = "F-001",
        ),
      ),
    ).copy(
      disposition = GoalSubtaskReviewDisposition.PAUSED,
      reviewedDeltaDigest = immutableDigest,
      remediationBaseSha = "9".repeat(40),
    )
    checkNotNull(harness.goalContinuationRecorder.updateReviewState(WORKFLOW_ID) { paused })
    harness.seedRawReviewResults(paused)
    val generationBefore = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)[
      "feature_task_runtime_review_generation",
    ]

    // A short run still executes reopenCappedReviewOnChangedDelta before the loop.
    harness.runner.run(
      harness.request().copy(
        transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
          forwardPhaseIds = listOf("preplan"),
          backwardEdges = emptyList(),
        ),
      ),
    )

    val after = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(GoalSubtaskReviewDisposition.PAUSED, after.disposition)
    assertEquals("9".repeat(40), after.remediationBaseSha, "staleness must not heal the remediation base")
    assertEquals(0, git.goalReviewRecoverCalls, "recovery belongs to review preparation, not staleness")
    assertEquals(
      generationBefore,
      harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)["feature_task_runtime_review_generation"],
    )
  }

  @Test
  @Suppress("LongMethod") // capped-review staleness harness with changed immutable digest
  fun `cappedReviewIsStale still reopens when the immutable base digest changed despite unreachable remediation`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-stale-changed-immutable")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val judgedDigest = GoalSubtaskReviewInput(
      reviewBaseSha = "0".repeat(40),
      currentHeadSha = COMMITTED_HEAD_SHA,
      trackedDelta = "old-delta\n",
      ownedUntrackedPatches = "",
    ).deltaDigest
    git.goalReviewBuildResults += GoalSubtaskReviewInputResult(
      status = "error",
      error = "Persisted review base '${"9".repeat(40)}' is not an ancestor of current HEAD.",
      failureReason = GoalSubtaskReviewInputFailureReason.BASE_NOT_ANCESTOR,
    )
    git.goalReviewBuildResults += GoalSubtaskReviewInputResult(
      status = "ok",
      input = GoalSubtaskReviewInput(
        reviewBaseSha = "0".repeat(40),
        currentHeadSha = COMMITTED_HEAD_SHA,
        trackedDelta = "new-delta\n",
        ownedUntrackedPatches = "",
      ),
    )
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    check(
      harness.goalContinuationRecorder.recordGoalContinuationState(
        GoalContinuationStateRecordRequest(
          workflowId = WORKFLOW_ID,
          continuation = FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = ISSUE_KEY,
            subtaskId = 5,
            suppressPr = true,
            goalBranch = "feat/existing-runtime-branch",
            parentWorkflowId = "wfl-parent",
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ),
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    harness.seedReviewPhase("completed", 1, validJsonOutput("review"), reviewPassNumber = 1)
    val paused = GoalSubtaskReviewState.initial(
      reviewBaseSha = "0".repeat(40),
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ).reserveNextPass().completeReservedPass(
      verdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      unresolvedFindingCount = 1,
      findings = listOf(
        GoalSubtaskReviewCompactFinding(
          severity = "blocker",
          label = "StaleCap",
          text = "unresolved",
          findingId = "F-001",
        ),
      ),
    ).copy(
      disposition = GoalSubtaskReviewDisposition.PAUSED,
      reviewedDeltaDigest = judgedDigest,
      remediationBaseSha = "9".repeat(40),
    )
    checkNotNull(harness.goalContinuationRecorder.updateReviewState(WORKFLOW_ID) { paused })
    harness.seedRawReviewResults(paused)

    harness.runner.run(
      harness.request().copy(
        transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
          forwardPhaseIds = listOf("preplan"),
          backwardEdges = emptyList(),
        ),
      ),
    )

    val after = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(GoalSubtaskReviewDisposition.PENDING, after.disposition)
    assertNull(after.remediationBaseSha, "invalidation resets review state; recovery is not the staleness path")
    assertEquals(0, git.goalReviewRecoverCalls)
  }

  @Test
  fun `crash reconciliation preserves a changes requested disposition without structured findings`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )
    harness.runner.run(
      harness.request().copy(
        transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
          forwardPhaseIds = listOf("preplan"),
          backwardEdges = emptyList(),
        ),
      ),
    )
    harness.goalContinuationRecorder.reserveGoalReviewPass(WORKFLOW_ID)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase(
      "review",
      "completed",
      1,
      phaseAgent("review"),
      """
        {"contract_version":"0.2","phase_id":"review","status":"completed","summary":"Review requests changes.","verdict":"changes_requested","produced_outputs":{"summary":"full evidence remains durable"}}
      """.trimIndent(),
    )

    harness.runner.run(
      harness.request().copy(
        transitionsOverride = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
          forwardPhaseIds = listOf("preplan", "plan", "implement", "audit", "review"),
          backwardEdges = emptyList(),
        ),
      ),
    )

    val state = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, state.completedPassCount)
    val passResult = state.passResults.single()
    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, passResult.verdict)
    assertEquals(1, passResult.unresolvedFindingCount)
  }

  @Test
  fun `goal-continuation outcome struct carries finalizingAgentId and participatingAgentIds`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-attribution-struct")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(validJsonOutput("commit_push")))

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val subtaskOutcome =
      requireNotNull(completed.subtaskOutcome) { "subtaskOutcome must be present for a goal-continuation run" }
    assertEquals(
      phaseAgent("commit_push"),
      subtaskOutcome.finalizingAgentId,
      "finalizingAgentId on the outcome struct must be the commit_push phase agent",
    )
    assertTrue(
      subtaskOutcome.participatingAgentIds.isNotEmpty(),
      "participatingAgentIds on the outcome struct must be non-empty for a completed goal-continuation run",
    )
  }

  // SKILL-190 AC-002: the runtime commits from the agent's outcome message, so an agent that emits no
  // message must stop the subtask rather than let a provisional checkpoint subject reach a pushed commit.
  @Test
  fun `goal-continuation blocks at commit_push when the agent supplies no outcome message`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goal-no-message")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also { it.headCommitShaValue = COMMITTED_HEAD_SHA }
    val harness = goalContinuationHarness(repoRoot, git, goalContinuationLauncher(COMMIT_PUSH_NO_SHA_OUTPUT))

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("commit_push", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "commit_push_result.message")
    assertTrue(git.pushedBranches.isEmpty(), "a rejected handoff must not push")
    assertTrue(git.leasePushedBranches.isEmpty(), "a rejected handoff must not force-push")
    assertEquals(COMMITTED_HEAD_SHA, git.headCommitShaValue, "a rejected handoff must not move HEAD")
  }

  @Test
  fun `non-goal-continuation run never measures git head for the outcome`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
      .also { it.headCommitShaValue = "should-not-read" }
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
      ),
    )

    harness.runner.run(harness.request())

    assertEquals(0, git.headCommitShaCalls, "a non-goal-continuation run must not measure git HEAD for an outcome")
  }
}

/** AC-014: the goal-child audit checkpoint is scoped to the child's own base and inventory. */
class FeatureTaskRuntimeCheckpointScopeTest {
  @Test
  fun `linear checkpoint inventory excludes runtime spec scratch while preserving code paths`() {
    val paths = reconcileCheckpointPathInventory(
      repoRoot = Path.of("/repo"),
      issueKey = "SKILL-146",
      specReference = ".feature-specs/SKILL-146-least-context/spec.md",
      paths = listOf(
        ".feature-specs/SKILL-146-least-context/spec.md",
        ".feature-specs/SKILL-146-remediation/notes.md",
        "runtime-domain/Changed.kt",
      ),
    )

    assertEquals(listOf("runtime-domain/Changed.kt"), paths)
  }

  @Test
  fun `local checkpoint inventory excludes feature spec scratch while preserving code paths`() {
    val paths = reconcileCheckpointPathInventory(
      repoRoot = Path.of("/repo"),
      issueKey = "SKILL-146",
      specReference = ".feature-specs/SKILL-146-least-context/spec.md",
      paths = listOf(
        ".feature-specs/SKILL-146-least-context/spec.md",
        ".feature-specs/SKILL-146-remediation/notes.md",
        "runtime-domain/Changed.kt",
      ),
    )

    assertEquals(listOf("runtime-domain/Changed.kt"), paths)
  }

  @Test
  fun `checkpoint inventory excludes the collapsed feature-specs directory`() {
    val paths = reconcileCheckpointPathInventory(
      repoRoot = Path.of("/repo"),
      issueKey = "SKILL-146",
      specReference = ".feature-specs/SKILL-146-least-context/spec.md",
      paths = listOf(
        ".feature-specs",
        ".feature-specs/",
        "runtime-domain/Changed.kt",
      ),
    )

    assertEquals(listOf("runtime-domain/Changed.kt"), paths)
  }

  @Test
  fun `goal-child audit checkpoint scopes owned paths to the child's own base and baseline inventory`() {
    val harness = checkpointScopeHarness()

    val report = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)

    val auditBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["audit"])
    assertContains(auditBriefing.briefingText, "base_ref: ${"0".repeat(40)}")
    assertContains(auditBriefing.briefingText, "- runtime-domain/Child.kt")
    assertContains(auditBriefing.briefingText, "- runtime-domain/Committed.kt")
    assertContains(auditBriefing.briefingText, "- runtime-domain/Remediation.kt")
    assertContains(auditBriefing.briefingText, "- runtime-domain/Renamed.kt")
    assertFalse(
      auditBriefing.briefingText.contains("- $SPEC_REFERENCE"),
      "the local feature spec is workflow input, not an audit or commit path",
    )
    assertFalse(
      auditBriefing.briefingText.contains("spec_subtask_9_sibling"),
      "a sibling subtask's baseline path must not enter the goal-child audit projection",
    )
    assertFalse(
      auditBriefing.briefingText.contains(".feature-specs/SKILL-137/sibling"),
      "no entry from a sibling subtask's untracked directory may enter the goal-child audit projection",
    )
    assertEquals(
      listOf(
        "runtime-domain/Child.kt",
        "runtime-domain/Committed.kt",
        "runtime-domain/Remediation.kt",
        "runtime-domain/Renamed.kt",
      ),
      requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID)).workflowOwnedPaths,
      "the checkpoint must union durable, committed, and remediation implementation paths",
    )
  }

  private fun checkpointScopeHarness(): RunnerHarness {
    val siblingPaths = listOf(
      ".feature-specs/SKILL-137/sibling/spec_subtask_9_sibling.md",
      ".feature-specs/SKILL-137/sibling/notes.md",
    )
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch").also {
      it.repositoryFingerprintValue = "child-fingerprint-1"
      it.changedPathsBetweenCommitsValue = "runtime-domain/Committed.kt"
      // Both inventories are `ls-files`-shaped so a wholly-untracked sibling directory matches
      // entry-for-entry instead of leaking through a collapsed porcelain `dir/` entry (F-005).
      it.ownedPathsValue = listOf(
        "runtime-domain/Child.kt",
        "runtime-domain/Renamed.kt",
        "runtime-domain/Remediation.kt",
      ) + siblingPaths
    }
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), siblingPaths),
        ),
      ),
    )
    harness.seedCheckpointAudit(siblingPaths)
    return harness
  }

  private fun RunnerHarness.seedCheckpointAudit(baselineOwnedPaths: List<String> = emptyList()) {
    seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    recorder.recordResolvedBranch(
      WORKFLOW_ID,
      FeatureTaskRuntimeResolvedBranch(
        branch = "feat/existing-runtime-branch",
        reviewBaseSha = "0".repeat(40),
        baselineOwnedPaths = baselineOwnedPaths,
        workflowOwnedPaths = listOf("runtime-domain/Child.kt"),
      ),
    )
  }

  @Test
  fun `an unmeasurable owned-path read blocks audit instead of rendering an empty scope`() {
    // An empty inventory reads as "this scope owns nothing", so an audit given it can conclude no work
    // exists and pass criteria never implemented. An unmeasurable read must not produce that answer;
    // it drops the checkpoint, and the refresh_from_repository receipt edge then rejects the launch.
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.repositoryFingerprintValue = "child-fingerprint-1"
    git.ownedPathsResult = WorkflowGitOperationResult(status = "error", value = "")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
      ),
    )
    harness.seedCheckpointAudit()

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "checkpoint")
    assertTrue(
      harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["audit"] == null,
      "no audit briefing may be persisted against an unmeasurable repository scope",
    )
  }

  @Test
  fun `an owned-path inventory past the checkpoint limit blocks the phase instead of unwinding the run`() {
    // The rendered scope sits inside the briefing framing ceiling, whose overflow throw is untyped and
    // uncaught: it would unwind past the handler that already persisted STATUS_RUNNING and leave the
    // row running with no blocked reason. The cap turns that into a typed durable block.
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.repositoryFingerprintValue = "child-fingerprint-1"
    git.ownedPathsValue = (1..2_000).map { "runtime-domain/Generated$it.kt" }
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
      ),
    )
    harness.seedCheckpointAudit()

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "owned-path inventory holds 2000 entries")
    assertTrue(
      harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["audit"] == null,
      "no audit briefing may be persisted from an over-limit repository scope",
    )
  }

  @Test
  fun `an owned-path inventory under the count cap but over the byte ceiling blocks instead of unwinding the run`() {
    // F-001: the count cap (<=500) does not bound bytes. ~200 long but realistic paths clear the count
    // cap yet render past the 65536-byte briefing framing ceiling. The framing throw is now typed and
    // caught at the launch seam, so the audit phase blocks durably instead of unwinding past the
    // STATUS_RUNNING persist and crash-looping on every resume.
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.repositoryFingerprintValue = "child-fingerprint-1"
    git.ownedPathsValue = (1..200).map { "runtime-domain/model/${"segment".repeat(50)}/Generated$it.kt" }
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
      ),
    )
    harness.seedCheckpointAudit()

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals("audit", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "ceiling")
    assertTrue(
      harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["audit"] == null,
      "no audit briefing may be persisted when its framing overflows the byte ceiling",
    )
  }
}

class FeatureTaskRuntimeRunnerSpecLifecycleTest {
  @Test
  fun `standalone run does not mutate spec status before commit_push`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-spec-status")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\n---\n\n# Spec\n")
    var specAtCommitLaunch: String? = null
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "commit_push") {
          specAtCommitLaunch = Files.readString(specPath)
        }
        facts(defaultPhaseOutput(request))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertContains(requireNotNull(specAtCommitLaunch), "status: Pending")
    assertContains(Files.readString(specPath), "status: Pending")
  }

  @Test
  fun `goal-continuation run does not mutate its spec status`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-spec-status-goal")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\n---\n\n# Spec\n")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = repoRoot,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertContains(Files.readString(specPath), "status: Pending")
  }

  @Test
  fun `single_spec linear run deletes the spec scratch dir on terminal success`() {
    // AC2: a single_spec linear-mode run deletes the local spec scratch only on terminal success.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-linear-delete")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\nspec_source: linear\n---\n\n# Spec\n")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(listOf(specPath.parent), harness.specScratchStore.deletedDirectories)
    assertFalse(Files.exists(specPath.parent), "linear single_spec scratch must be deleted on success")
  }

  @Test
  fun `single_spec linear run that blocks leaves the spec scratch intact`() {
    // AC3: an aborted/blocked linear run leaves the scratch intact and resumable.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-linear-block")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\nspec_source: linear\n---\n\n# Spec\n")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "commit_push") COMMIT_PUSH_BLOCKED_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertTrue(harness.specScratchStore.deletions.isEmpty(), "a blocked run must not delete the scratch")
    assertTrue(Files.exists(specPath), "blocked linear run leaves the spec scratch intact")
  }

  @Test
  fun `local-mode run never deletes the spec scratch`() {
    // Local mode (default, no spec_source line) keeps the spec scratch on disk and deletes nothing.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-local-no-delete")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\n---\n\n# Spec\n")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertTrue(harness.specScratchStore.deletions.isEmpty(), "local mode must not delete the scratch")
    assertTrue(Files.exists(specPath), "local mode keeps the spec scratch on disk")
  }

  @Test
  fun `goal-continuation linear subtask run leaves spec deletion to the goal runner`() {
    // AC2 ownership: the runner deletes only single_spec scratch; decomposed deletion is the goal
    // runner's responsibility, so a goal-continuation subtask run must not delete its spec.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-linear-goalcont")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\nspec_source: linear\n---\n\n# Spec\n")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        repoRoot = repoRoot,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertTrue(harness.specScratchStore.deletions.isEmpty(), "the goal runner owns goal-continuation deletion")
    assertTrue(Files.exists(specPath), "goal-continuation run must leave the subtask spec for the goal runner")
  }

  @Test
  fun `resume of a durably complete decompose plan reports decomposed without advancing to implement`() {
    // PC-F001 (resume fall-through): PLAN is durably completed as a non-goal-continuation decompose
    // outcome, but the process crashed before the decompose terminal was observed. A re-run must
    // re-derive the decompose stop and terminate at planning, never advancing to implement.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-resume")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), DECOMPOSE_PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val decomposed = assertIs<FeatureTaskRuntimeRunReport.Decomposed>(report)
    assertEquals(2, decomposed.subtaskSpecPaths.size)
    // The implement agent must never launch: a decompose terminal must not advance the run.
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    assertTrue(harness.launchedPhaseOrder().isEmpty(), "no phase agent relaunches on a complete-plan resume")
    val terminal = requireNotNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
    assertContains(terminal.reason, "needs ordered subtasks")
  }

  @Test
  fun `resume reconstructs an already-recorded decompose terminal without rewriting specs`() {
    // PC-F001 (idempotent resume): when a decompose terminal is already durably recorded, the resume
    // reconstructs the Decomposed report from it rather than re-running the shared prep write path.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-idempotent")
    val firstHarness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )
    val first = assertIs<FeatureTaskRuntimeRunReport.Decomposed>(firstHarness.runner.run(firstHarness.request()))
    // Only the write path (resolveFromPlanOutput) emits DecomposedAtPlanning; the loadDecomposeTerminal
    // early-return reconstruction path does not. A single emission across BOTH runs therefore proves
    // the resume bypassed the writer rather than re-running the shared prep write path.
    val emissionsAfterFirst = firstHarness.events.count { it is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning }
    assertEquals(1, emissionsAfterFirst, "the first run writes the decomposition and emits exactly once")

    val resumed = assertIs<FeatureTaskRuntimeRunReport.Decomposed>(firstHarness.runner.run(firstHarness.request()))
    assertEquals(first.decompositionManifestPath, resumed.decompositionManifestPath)
    assertEquals(first.subtaskSpecPaths, resumed.subtaskSpecPaths)
    assertTrue(resumed.subtaskSpecPaths.isNotEmpty())
    val emissionsAfterResume = firstHarness.events.count { it is FeatureTaskRuntimeRunEvent.DecomposedAtPlanning }
    assertEquals(
      1,
      emissionsAfterResume,
      "the resume reconstructs from the recorded terminal and must NOT re-emit (i.e. must not re-run the writer)",
    )
  }

  @Test
  fun `malformed decompose package blocks loudly instead of throwing or advancing`() {
    // PC-F001 (crash guard): a plan envelope declaring mode=decompose but with a malformed package
    // (a subtask missing required fields) must produce a diagnosable Blocked outcome, not crash.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-malformed")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") MALFORMED_DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "malformed decomposition package")
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    // The block is durable and visible to status: the plan phase carries a terminal blocked record.
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertTrue(requireNotNull(planRecord.blockedReason).isNotBlank())
    assertNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
  }

  @Test
  fun `decoder-valid but writer-invalid decompose package blocks loudly on a fresh run`() {
    // PC-F001 residual: a plan envelope declaring mode=decompose with a package the typed decoder
    // accepts (every required field present + correctly typed) but the writer rejects on a
    // business rule (subtask ids descend [2, 1]) throws InvalidFeatureSpecPreparationRequestError
    // from the write path. That exception extends SkillBillRuntimeException (NOT
    // IllegalArgumentException) and previously escaped resolve()'s catch and crashed run(). It must
    // now produce a diagnosable Blocked terminal, never an uncaught throw, and never launch implement.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-writer-invalid")
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(if (phaseId == "plan") WRITER_INVALID_DECOMPOSE_PLAN_OUTPUT else validJsonOutput(phaseId))
      },
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "malformed decomposition package")
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertTrue(requireNotNull(planRecord.blockedReason).isNotBlank())
    assertNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
  }

  @Test
  fun `decoder-valid but writer-invalid decompose package blocks loudly on a plan-complete resume`() {
    // PC-F001 residual (resume): same writer-rejected package, but PLAN is already durably complete
    // (crash after PLAN before the terminal was observed). The resume re-derives the decompose stop
    // from the persisted output and must Block, never crash or advance to implement.
    val repoRoot = Files.createTempDirectory("skillbill-runtime-decompose-writer-invalid-resume")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(repoRoot = repoRoot, useRealDecompositionPlanner = true),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), WRITER_INVALID_DECOMPOSE_PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("plan", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "malformed decomposition package")
    assertTrue(harness.launchedPhaseOrder().isEmpty(), "no phase agent relaunches on a complete-plan resume")
    assertTrue(harness.launchedPhaseOrder().none { it == "implement" })
    val planRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["plan"])
    assertEquals("blocked", planRecord.status)
    assertTrue(requireNotNull(planRecord.blockedReason).isNotBlank())
    assertNull(harness.decomposeTerminalRecorder.loadDecomposeTerminal(WORKFLOW_ID))
  }

  @Test
  fun `a block during a backward-edge re-entry persists the loop context and enforces the cap across the crash`() {
    // MAJOR 1 (AC4/AC8): a re-entered phase that fires its edge (edge iteration 1) then BLOCKS on an
    // infra failure must persist the runtime-minted loop context on its terminal blocked record. The
    // bug overwrote the running record's loop_id/edge_iteration with a context-less blocked record, so
    // on resume the per-edge watermark reset to 0 and the edge could fire perEdgeCap more times after
    // every crash-at-blocked-re-entry, bypassing the cap.
    var preplanLaunches = 0
    var crashOnReentry = true
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when {
          phaseId == "preplan" && ++preplanLaunches == 2 && crashOnReentry -> spawnFailedFacts()
          phaseId == "plan" -> facts(verdictPlanOutput("needs_fix"))
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: preplan -> plan needs_fix fires the edge (iteration 1) -> preplan re-enters and crashes.
    val firstReport = harness.runner.run(harness.request(PLAN_FIX_CYCLE))

    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals("preplan", firstBlocked.lastIncompletePhase)
    // The terminal blocked record retained the loop context — the watermark the bug dropped.
    val blockedPreplan = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["preplan"])
    assertEquals("blocked", blockedPreplan.status)
    assertEquals("plan-fix", blockedPreplan.loopId)
    assertEquals(1, blockedPreplan.edgeIteration)

    // Run 2 (resume): the crash heals and the cycle continues. The surviving watermark means the
    // resume mints the NEXT edge iteration (the cap), never restarting at 1, so across both runs the
    // edge fires exactly 1..cap. Had the blocked record dropped the context, resume would re-mint
    // iteration 1 and the edge could fire perEdgeCap more times, bypassing the cap.
    crashOnReentry = false
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request(PLAN_FIX_CYCLE)))
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
      .mapNotNull { it.edgeIteration }
    assertEquals((1..PLAN_FIX_CAP).toList(), edgeIterations, "the edge fired 1..cap across the crash, never restarting")
  }
}

// SKILL-85 Subtask 4: the M1 review-driven implement_fix loop matrix over the real phase
// topology, kept in a sibling class so the primary runner test class stays within its size
// budget while sharing the same file-private run harness.
class FeatureTaskRuntimeReviewFixLoopTest {
  @Test
  fun `schema-rejected evidence is persisted apart from the phase output artifact`() {
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("write_history")),
      agentAssignment = phasePerAgentAssignment(),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    val writeHistoryRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["write_history"])
    assertEquals("blocked", writeHistoryRecord.status)
    assertNull(writeHistoryRecord.rejectedOutput)
    assertNull(
      writeHistoryRecord.outputArtifact,
      "rejected evidence must never land in output_artifact, which resume hydration re-validates",
    )
  }

  @Test
  fun `a completed record with an unparseable output artifact still loud-fails on resume`() {
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("plan")),
      agentAssignment = phasePerAgentAssignment(),
    )
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), "not a json object")

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.runner.run(harness.request())
    }
  }

  // Legacy records stored schema-rejected evidence in output_artifact. Hydrating resume state must tolerate
  // it, or those workflows can never be resumed again.
  @Test
  fun `a blocked phase carrying schema-rejected output stays resumable`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    harness.seedPhase("review", "blocked", 2, phaseAgent("review"), "Cleaned up the last pending wait timer.")

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("completed", reviewRecord.status)
  }

  @Test
  fun `a legacy schema-gate review block relaunches on resume instead of re-blocking`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, phaseAgent("implement"), IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, phaseAgent("audit"), VALID_AUDIT_OUTPUT)
    harness.seedBlockedPhase(
      "review",
      attemptCount = 2,
      phaseAgent("review"),
      "Goal-subtask review output failed schema validation after its reserved pass; " +
        "refusing an unaccounted relaunch. <root> must be an object.",
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(harness.launchOrder().contains("review"))
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("completed", reviewRecord.status)
    assertEquals(3, reviewRecord.attemptCount)
  }

  @Test
  fun `goal review retries schema-invalid output inside its already-reserved pass`() {
    val harness = runnerHarness(
      runtimeConfig = RuntimeHarnessConfig(
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(1, harness.launchOrder().count { it == "review" })
    val state = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(
      1,
      state.completedPassCount,
      "runtime-owned review settles the reserved pass in one driver cycle",
    )
    assertEquals(null, state.reservedPassNumber)
  }

  @Test
  fun `goal review schema retries stay on the reserved pass past the former cap`() {
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
      ),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(1, harness.launchOrder().count { it == "review" })
    val state = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, state.completedPassCount)
    assertEquals(null, state.reservedPassNumber)
  }

  // --- SKILL-85 Subtask 4: M1 review-driven implement_fix loop over the real phase topology ------

  @Test
  fun `m1 finished telemetry carries the review fix iteration count after a loop ran`() {
    val harness = telemetryRunnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = 2),
      runtimeConfig = reviewFixRuntimeConfig(2),
    )

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val finished = harness.lifecycle.finishedRecords.single()
    assertEquals(1, finished.reviewFixIterationCount, "the single review->fix iteration is reflected in telemetry")
  }

  @Test
  fun `m1 finished telemetry reports zero review fix iterations on a clean run`() {
    // The additive count is 0 when the review_fix loop never fired (a clean forward run).
    val harness = telemetryRunnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = 1),
      runtimeConfig = reviewFixRuntimeConfig(1),
    )

    val report = harness.runner.run(harness.request)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(0, harness.lifecycle.finishedRecords.single().reviewFixIterationCount)
  }

  // (a) AC3/AC10: an approved review advances to audit and never launches the loop-only fix phase.
  @Test
  fun `m1 approved review advances to audit without launching implement_fix`() {
    val harness = runnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = 1),
      runtimeConfig = reviewFixRuntimeConfig(1),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals(AGENT_LAUNCHED_PHASES, launched, "a clean run launches the forward pipeline, skipping implement_fix")
    assertTrue(launched.none { it == "implement_fix" })
  }

  @Test
  fun `m1 Major review finding launches implement_fix once then advances to validate`() {
    val git = RecordingWorkflowGitOperations().apply {
      repositoryFingerprintValue = "before-fix"
    }
    var reviewPasses = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement_fix") git.repositoryFingerprintValue = "after-fix"
        facts(validJsonOutput(phaseId))
      },
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        reviewDriver = skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver { request ->
          reviewPasses += 1
          val findings = listOf(
            skillbill.review.model.ParallelReviewMergedFinding(
              fNumber = "F-001",
              agentIds = listOf(request.agent1Id),
              severity = skillbill.review.model.ParallelReviewSeverity.MAJOR,
              confidence = "High",
              location = "Foo.kt:1",
              description = REVIEW_BLOCKER_MESSAGE,
            ),
          )
          skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
            mergeResult = skillbill.review.model.ParallelReviewMergeResult(
              findings = findings,
              formattedOutput = "findings",
            ),
          )
        },
      ),
    )

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchOrder()
    assertEquals(1, launched.count { it == "review" })
    assertEquals(1, launched.count { it == "implement_fix" }, "Major findings must launch implement_fix once")
    assertTrue(launched.indexOf("review") < launched.indexOf("implement_fix"))
    assertTrue(launched.indexOf("implement_fix") < launched.indexOf("validate"))
  }

  @Test
  fun `m1 changes_requested spawns implement_fix with the findings then advances to validate`() {
    val git = RecordingWorkflowGitOperations().apply {
      repositoryFingerprintValue = "before-fix"
    }
    val harness = runnerHarness(
      launcher = reviewFixLauncher(
        convergeOnReview = 2,
        onPhaseLaunch = { phaseId ->
          if (phaseId == "implement_fix") git.repositoryFingerprintValue = "after-fix"
        },
      ),
      runtimeConfig = reviewFixRuntimeConfig(2, git),
    )

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchOrder()
    assertEquals(1, launched.count { it == "implement_fix" }, "one fix iteration before advancing")
    assertEquals(1, launched.count { it == "review" }, "review runs exactly once")
    val firstReview = launched.indexOf("review")
    val fixIndex = launched.indexOf("implement_fix")
    assertTrue(firstReview < fixIndex, "implement_fix runs after the triggering review")
    assertTrue(fixIndex < launched.indexOf("validate"), "validate runs after implement_fix without re-review")
    val fixBriefing = requireNotNull(harness.recorder.loadPhaseBriefings(WORKFLOW_ID).orEmpty()["implement_fix"])
    assertContains(fixBriefing.briefingText, REVIEW_BLOCKER_MESSAGE)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
    assertTrue(loopEdges.all { it.loopId == "review_fix" })
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "review" })
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      requireNotNull(harness.runInvariantsStore.resolve(WORKFLOW_ID)).codeReviewMode,
    )
  }

  @Test
  fun `resume without a review-mode request retains the durable mode for a re-review`() {
    val harness = runnerHarness(
      runtimeConfig = RuntimeHarnessConfig(
        reviewDriver = failingReviewDriver(
          failOnPass = 1,
          failureReason = "failed to launch: the agent process could not be spawned.",
        ),
      ),
    )

    val first = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(first)

    val resumed = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumed)
    assertEquals(2, harness.launchOrder().count { it == "review" })
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "review" })
    assertEquals(
      CodeReviewExecutionMode.INLINE,
      requireNotNull(harness.runInvariantsStore.resolve(WORKFLOW_ID)).codeReviewMode,
    )
  }

  @Test
  fun `failed review resumes the same durably reserved inline pass`() {
    val harness = runnerHarness(
      runtimeConfig = RuntimeHarnessConfig(
        reviewDriver = failingReviewDriver(
          failOnPass = 1,
          failureReason = "spawn failed",
        ),
      ),
    )

    val first = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(first)
    val blockedReview = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("blocked", blockedReview.status)
    assertEquals(1, blockedReview.reviewPassNumber)
    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    assertEquals(2, harness.launchOrder().count { it == "review" })
    assertTrue(harness.launchedPromptPhaseOrder().none { it == "review" })
    val completedReview = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("completed", completedReview.status)
    assertEquals(1, completedReview.reviewPassNumber)
  }

  @Test
  fun `resume rejects a changed review mode before opening or launching`() {
    val harness = runnerHarness()
    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE)),
    )
    val launchCount = harness.launcher.requests.size

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.AUTO),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "Cannot change code-review mode on resume")
    assertEquals(launchCount, harness.launcher.requests.size)
  }

  @Test
  fun `m1 cap exhaustion advances to validate without re-review`() {
    val harness = runnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = 2),
      runtimeConfig = reviewFixRuntimeConfig(2),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchOrder()
    assertEquals(
      1,
      harness.launchedPromptPhaseOrder().count { it == "implement_fix" },
      "one fix iteration before advancing",
    )
    assertEquals(1, launched.count { it == "review" })
    assertTrue(launched.indexOf("implement_fix") < launched.indexOf("validate"))
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
  }

  @Test
  fun `m1 an unresolved Blocker advances to validate after one fix round`() {
    val harness = runnerHarness(
      launcher = reviewFixLauncher(convergeOnReview = 12),
      runtimeConfig = reviewFixRuntimeConfig(12),
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchOrder()
    assertTrue(
      launched.indexOf("audit") < launched.indexOf("review"),
      "audit settles satisfied before review is reachable",
    )
    assertEquals(1, launched.count { it == "review" })
    assertEquals(1, launched.count { it == "implement_fix" })
    assertTrue(launched.indexOf("implement_fix") < launched.indexOf("validate"))
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals(1, reviewRecord.reviewPassNumber)
    val loopEdges = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
    assertEquals(listOf(1), loopEdges.mapNotNull { it.edgeIteration })
  }

  @Test
  fun `m1 review_fix fires once then advances even when Blocker findings remain`() {
    listOf(1, 2, 4, 10).forEach { blockingPasses ->
      val harness = runnerHarness(
        launcher = reviewFixLauncher(convergeOnReview = blockingPasses + 1),
        runtimeConfig = reviewFixRuntimeConfig(blockingPasses + 1),
      )

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
      val launched = harness.launchOrder()
      assertEquals(1, launched.count { it == "review" })
      assertEquals(1, launched.count { it == "implement_fix" })
      val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
        .mapNotNull { it.edgeIteration }
      assertEquals(listOf(1), edgeIterations, "$blockingPasses blocking passes still yield one fix round")
    }
  }

  // (f) AC5/AC10: an idempotent re-entry — implement_fix's reconciliation gate is enforced, so a fix
  // output that omits the reconciliation report blocks loudly rather than silently double-applying.
  @Test
  fun `m1 implement_fix without a reconciliation report blocks on the idempotency gate`() {
    var implementFixLaunches = 0
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "implement_fix" -> {
            implementFixLaunches += 1
            facts(
              if (implementFixLaunches == 1) {
                """{"contract_version":"0.2","phase_id":"implement_fix","status":"completed",""" +
                  """"summary":"fix","produced_outputs":{"changed_files":["src/Foo.kt"]}}"""
              } else {
                validJsonOutput(phaseId)
              },
            )
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
      runtimeConfig = reviewFixRuntimeConfig(2),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals(1, implementFixLaunches, "a one-attempt budget leaves no relaunch")
    assertGateBlockNamesRule(blocked.blockedReason, "mutating-reconciliation")
    assertTrue(
      harness.io.database.rejectedDiagnostics()
        .first { it.metadata.phaseId == "implement_fix" }.metadata.reason.contains("reconcil"),
    )
  }

  // (g) AC5/AC10: a crash mid-loop (implement_fix re-entered then spawn-fails) resumes at the correct
  // phase with the durable loop context preserved (the watermark is never reset), then continues the
  // loop to convergence. The edge counter advances monotonically across the crash, never restarting.
  @Test
  fun `m1 crash during implement_fix resumes with the loop context preserved and converges`() {
    var fixLaunches = 0
    var crashOnFix = true
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when (phaseId) {
          "implement_fix" -> {
            fixLaunches += 1
            if (fixLaunches == 1 && crashOnFix) spawnFailedFacts() else facts(validJsonOutput(phaseId))
          }
          else -> facts(validJsonOutput(phaseId))
        }
      },
      runtimeConfig = reviewFixRuntimeConfig(2),
    )

    // Run 1: review changes_requested fires the edge (iteration 1) -> implement_fix re-enters and crashes.
    val firstReport = harness.runner.run(harness.request())
    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals("implement_fix", firstBlocked.lastIncompletePhase)
    // AC5: the terminal blocked fix record retained the review_fix loop context (the watermark the
    // resume reconstruction relies on), not a context-less record that would reset the cap.
    val fixRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement_fix"])
    assertEquals("review_fix", fixRecord.loopId)
    assertEquals(1, fixRecord.edgeIteration)

    // Run 2 (resume): the crash heals; the loop re-enters the fix from the preserved watermark and the
    // re-review then approves, advancing the run to completion.
    crashOnFix = false
    val resumeReport = harness.runner.run(harness.request())
    assertIs<FeatureTaskRuntimeRunReport.Completed>(resumeReport)
    // The edge iterations are monotonic and bounded across the crash (never reset to a fresh 1).
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
      .mapNotNull { it.edgeIteration }
    assertEquals(edgeIterations.sorted(), edgeIterations, "edge iterations advance monotonically across the crash")
    assertEquals(edgeIterations.toSet().size, edgeIterations.size, "no edge iteration repeats after the crash")
    assertTrue(edgeIterations.all { it <= 1 }, "the cap is never exceeded across the crash")
  }

  @Test
  fun `m1 review_fix loop resumed at a prior iteration completes the single fix round`() {
    val harness = runnerHarness()
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, auditSatisfiedOutput())
    harness.seedReviewPhase("completed", 1, reviewFindingsOutput(changesRequested = true), 1)
    harnessPendingVerifyFindingIds = listOf(REVIEW_FIX_BLOCKER_FINDING_ID)
    harness.seedPhase(
      "verify_findings",
      "completed",
      1,
      phaseAgent("verify_findings"),
      validJsonOutput("verify_findings"),
    )
    harness.seedReentryPhase(
      "implement_fix",
      "completed",
      1,
      INVOKED_AGENT,
      validJsonOutput("implement_fix"),
      "review_fix",
      1,
    )
    harness.seedLoopEdge("implement_fix", "review_fix", 1)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertTrue(launched.none { it == "audit" }, "the seeded satisfied audit is reused, not relaunched")
    assertTrue(launched.none { it == "review" }, "review already completed; no second pass")
    assertTrue(launched.none { it == "implement_fix" }, "the seeded fix round is reused")
    assertTrue(launched.contains("validate"), "the run advances after the single fix round")
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
      .mapNotNull { it.edgeIteration }
    assertEquals(listOf(1), edgeIterations)
  }

  @Test
  fun `ledger-only review fix resumes at implement fix without consuming the edge`() {
    val harness = runnerHarness()
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedPhase("audit", "completed", 1, INVOKED_AGENT, auditSatisfiedOutput())
    harness.seedReviewPhase("completed", 1, reviewFindingsOutput(changesRequested = true), 1)
    harness.seedPhase(
      "verify_findings",
      "completed",
      1,
      phaseAgent("verify_findings"),
      verifyFindingsOutput(listOf(REVIEW_FIX_BLOCKER_FINDING_ID)),
    )
    harness.seedLoopEdge("implement_fix", "review_fix", 1)

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchedPromptPhaseOrder()
    assertEquals("implement_fix", launched.first())
    assertTrue(launched.none { it == "preplan" || it == "plan" })
    assertEquals(
      listOf(1),
      harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
        .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE && it.loopId == "review_fix" }
        .mapNotNull { it.edgeIteration },
    )
  }

  @Test
  fun `m1 crash with review attempt_count past the schema budget resumes without premature block`() {
    val harness = runnerHarness()
    harness.seedPhase("preplan", "completed", 1, INVOKED_AGENT, PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, INVOKED_AGENT, IMPLEMENT_OUTPUT)
    harness.seedReentryPhase(
      "implement_fix",
      "completed",
      1,
      INVOKED_AGENT,
      validJsonOutput("implement_fix"),
      "review_fix",
      1,
    )
    harness.seedReviewPhase(
      "running",
      3,
      reviewFindingsOutput(changesRequested = true),
      1,
    )

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val launched = harness.launchOrder()
    assertTrue(launched.contains("review"), "the resumed review relaunched rather than pre-blocking")
    assertTrue(launched.contains("validate"), "the run advances to validate after the single fix round")
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("completed", reviewRecord.status)
  }

  // (j) AC1/SKILL-85-F-003: a review output carrying NEITHER a structured verdict NOR a findings array
  // is missing every verification signal; it must fail loudly through the schema gate rather than
  // silently advancing to validation (prose alone cannot advance past a possible Blocker/Major).
  @Test
  fun `m1 review with neither verdict nor findings retries rather than silently advancing`() {
    val harness = runnerHarness()

    val report = harness.runner.run(harness.request())

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(1, harness.launchOrder().count { it == "review" })
    val reviewRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["review"])
    assertEquals("completed", reviewRecord.status)
    val artifact = requireNotNull(reviewRecord.outputArtifact)
    assertTrue(artifact.contains("\"findings\""), artifact)
    assertTrue(artifact.contains("\"review_run_id\""), artifact)
    assertTrue(artifact.contains("\"verdict\""), artifact)
  }
}

// Branch-setup establishment, resume re-attach, loud-fail blocks, durability/visibility, and
// resolved-branch idempotency through the runner, kept in a sibling class so the runner test class
// stays within its size budget while sharing the same file-private run harness. (Pure branch-setup
// decision logic lives in FeatureTaskRuntimeBranchSetupTest.)
class FeatureTaskRuntimeBranchSetupRunnerTest {
  @Test
  fun `starts on default branch creates and switches to the feature branch before implement`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(EXPECTED_FEATURE_BRANCH, completed.resolvedBranch)
    assertEquals(
      listOf(RecordingWorkflowGitOperations.CheckoutCall(EXPECTED_FEATURE_BRANCH, "main")),
      git.checkoutCalls,
    )
    val resolved = requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID))
    assertEquals(EXPECTED_FEATURE_BRANCH, resolved.branch)
    assertTrue(resolved.created)
    val branchEvent = assertIs<FeatureTaskRuntimeRunEvent.BranchResolved>(
      harness.events.first { it is FeatureTaskRuntimeRunEvent.BranchResolved },
    )
    assertTrue(branchEvent.created)
    // The preplan and plan phases are non-file-mutating; branch setup happens before implement.
    assertEquals(COMPLETED_PHASES_CLEAN_RUN, harness.launchOrder())
  }

  @Test
  fun `starts on a non-default branch reuses it without checking out a new branch`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/pre-created")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals("feat/pre-created", completed.resolvedBranch)
    assertTrue(git.checkoutCalls.isEmpty(), "reuse must not check out a new branch")
    val resolved = requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID))
    assertEquals("feat/pre-created", resolved.branch)
    assertEquals(false, resolved.created)
  }

  @Test
  fun `cannot establish a feature branch blocks loudly and launches no file-mutating phase`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      checkoutResult = WorkflowGitOperationResult(status = "error", error = "checkout exploded"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, EXPECTED_FEATURE_BRANCH)
    assertContains(blocked.blockedReason, "checkout exploded")
    assertContains(blocked.blockedReason, "default branch")
    // Preplan and plan launched (non-mutating), but no file-mutating phase ever launched.
    assertEquals(NON_FILE_MUTATING_PHASES.toList(), harness.launchOrder())
    assertTrue(harness.launchOrder().none { it !in NON_FILE_MUTATING_PHASES })
  }

  @Test
  fun `unreadable current branch blocks loudly and launches no file-mutating phase`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchResult = WorkflowGitOperationResult(status = "error", error = "git HEAD unreadable"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "git HEAD unreadable")
    assertContains(blocked.blockedReason, "default branch")
    assertTrue(git.checkoutCalls.isEmpty(), "an unreadable current branch must never check out")
    assertEquals(NON_FILE_MUTATING_PHASES.toList(), harness.launchOrder())
    assertTrue(
      harness.launchOrder().none { it !in NON_FILE_MUTATING_PHASES },
      "no file-mutating phase may launch when the current branch is unreadable",
    )
  }

  @Test
  fun `resume on default with a persisted branch re-attaches via exactly one checkout to it`() {
    // HEAD on main alone would trigger create+checkout of the convention branch; the persisted
    // branch must drive the decision, producing exactly one checkout to it and no second/divergent
    // branch. A divergent value proves the persisted load (not a fresh resolution) decided.
    val persistedBranch = "feat/persisted-resume-branch"
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(persistedBranch, baseBranch = "main", created = true)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(persistedBranch, completed.resolvedBranch)
    assertEquals(
      listOf(RecordingWorkflowGitOperations.CheckoutCall(persistedBranch, null)),
      git.checkoutCalls,
      "re-attach on default must perform exactly one checkout, targeting the persisted branch",
    )
    assertTrue(
      git.checkoutCalls.none { it.branch == EXPECTED_FEATURE_BRANCH },
      "re-attach must not create the freshly-resolved convention branch",
    )
    val branchEvent = assertIs<FeatureTaskRuntimeRunEvent.BranchResolved>(
      harness.events.first { it is FeatureTaskRuntimeRunEvent.BranchResolved },
    )
    assertEquals(persistedBranch, branchEvent.branch)
    assertTrue(branchEvent.reused)
    assertEquals(false, branchEvent.created)
    assertEquals(persistedBranch, requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID)).branch)
  }

  @Test
  fun `resume already on the persisted branch re-attaches without any checkout`() {
    val persistedBranch = "feat/persisted-resume-branch"
    val git = RecordingWorkflowGitOperations(currentBranchValue = persistedBranch)
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(persistedBranch, baseBranch = "main", created = true)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(persistedBranch, completed.resolvedBranch)
    assertTrue(git.checkoutCalls.isEmpty(), "HEAD already on the persisted branch must not check out")
    val branchEvent = assertIs<FeatureTaskRuntimeRunEvent.BranchResolved>(
      harness.events.first { it is FeatureTaskRuntimeRunEvent.BranchResolved },
    )
    assertEquals(persistedBranch, branchEvent.branch)
    assertTrue(branchEvent.reused)
    assertEquals(false, branchEvent.created)
    assertEquals(persistedBranch, requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID)).branch)
  }

  @Test
  fun `resume whose persisted branch no longer exists blocks loudly and creates no branch`() {
    val persistedBranch = "feat/deleted-between-runs"
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      existingBranches = emptySet(),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(persistedBranch, baseBranch = "main", created = true)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, persistedBranch)
    assertContains(blocked.blockedReason, "no longer exists")
    assertEquals(listOf(persistedBranch), git.branchExistsCalls)
    assertTrue(
      git.checkoutCalls.isEmpty(),
      "a missing persisted branch must never check out (would create a divergent branch): ${git.checkoutCalls}",
    )
    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(launchedMutating.isEmpty(), "no file-mutating phase may launch when re-attach fails: $launchedMutating")
  }

  @Test
  fun `resume blocks loudly when persisted branch existence cannot be verified`() {
    val persistedBranch = "feat/existence-unreadable"
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      branchExistsResult = WorkflowGitOperationResult(status = "error", error = "rev-parse exploded"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(persistedBranch, baseBranch = "main", created = true)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, persistedBranch)
    assertContains(blocked.blockedReason, "rev-parse exploded")
    assertTrue(git.checkoutCalls.isEmpty(), "an unverifiable persisted branch must never check out")
    assertTrue(harness.launchOrder().none { it !in NON_FILE_MUTATING_PHASES })
  }

  @Test
  fun `checkout that lands on a different branch blocks loudly and launches no file-mutating phase`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      landedBranchAfterCheckout = "feat/wrong-landing",
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "feat/wrong-landing")
    assertContains(blocked.blockedReason, EXPECTED_FEATURE_BRANCH)
    assertTrue(blocked.blockedReason.contains("HEAD is on"))
    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(
      launchedMutating.isEmpty(),
      "no file-mutating phase may launch when HEAD lands on the wrong branch: $launchedMutating",
    )
  }

  @Test
  fun `checkout that lands on a protected branch blocks loudly and launches no file-mutating phase`() {
    // A corrupt persisted branch name that is itself protected: the checkout reports landing on it
    // (landed == target), so the post-checkout guard must reject it via the protected-branch arm
    // rather than the wrong-branch arm.
    val protectedPersisted = "main"
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/pre-created")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch(protectedPersisted, baseBranch = "main", created = false)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "protected branch")
    assertContains(blocked.blockedReason, "main")
    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(
      launchedMutating.isEmpty(),
      "no file-mutating phase may launch when HEAD lands on a protected branch: $launchedMutating",
    )
  }

  @Test
  fun `resume already on a protected persisted branch blocks loudly and launches no file-mutating phase`() {
    // Same corrupt persisted branch as the checkout-protected test, but HEAD is already on the
    // protected branch. The no-op re-attach path must still reject it before launching implement.
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch("main", baseBranch = "main", created = false)
    harness.seedPhase("plan", "completed", 1, INVOKED_AGENT, PLAN_OUTPUT)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "protected branch")
    assertContains(blocked.blockedReason, "main")
    assertTrue(git.checkoutCalls.isEmpty(), "already-on-protected re-attach must not check out")
    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(
      launchedMutating.isEmpty(),
      "no file-mutating phase may launch when persisted branch is protected: $launchedMutating",
    )
  }

  @Test
  fun `no file-mutating phase launches while on the default branch`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      checkoutResult = WorkflowGitOperationResult(status = "error", error = "denied"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    harness.runner.run(harness.request())

    val launchedMutating = harness.launchOrder().filter { it !in NON_FILE_MUTATING_PHASES }
    assertTrue(launchedMutating.isEmpty(), "no file-mutating phase may launch on the default branch: $launchedMutating")
  }

  @Test
  fun `branch-setup block is durably visible to status, observability, and the ledger`() {
    val git = RecordingWorkflowGitOperations(
      currentBranchValue = "main",
      checkoutResult = WorkflowGitOperationResult(status = "error", error = "checkout exploded"),
    )
    val harness = runnerHarness(
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)

    // Durable status projection: the branch-setup block is no longer invisible (blockedCount=0,
    // implement merely running/pending); it surfaces as a first-class blocked phase with its reason.
    val status = requireNotNull(
      FeatureTaskRuntimeStatusService(
        harness.recorder,
        harness.runInvariantsStore,
        harness.decomposeTerminalRecorder,
      )
        .status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID)),
    )
    assertEquals(1, status.blockedCount)
    val implementStatus = status.phases.single { it.phaseId == "implement" }
    assertEquals("blocked", implementStatus.status)

    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("blocked", implementRecord.status)
    assertContains(requireNotNull(implementRecord.blockedReason), "checkout exploded")

    // Typed observability event mirrors the per-phase block path.
    val event = assertIs<FeatureTaskRuntimeRunEvent.BranchSetupBlocked>(
      harness.events.single { it is FeatureTaskRuntimeRunEvent.BranchSetupBlocked },
    )
    assertEquals("implement", event.phaseId)
    assertContains(event.blockedReason, "checkout exploded")

    // Append-only ledger carries the blocked entry for the audit trail.
    val ledgerEntry = requireNotNull(harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty())
      .single { it.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED }
    assertEquals("implement", ledgerEntry.phaseId)
    assertContains(requireNotNull(ledgerEntry.blockedReason), "checkout exploded")
  }

  @Test
  fun `resume after a recoverable branch-setup block re-attempts setup and launches the implement phase`() {
    // F-002: a prior run blocked at branch setup (transient git error) and persisted a blocked
    // record under "implement" keyed to the branch-setup sentinel agent. The operator fixes the git
    // condition and resumes; branch setup now succeeds, so the stale block must be superseded and the
    // implement phase must actually launch rather than re-block forever.
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    lateinit var harness: RunnerHarness
    var observedPreLaunchRecord = false
    harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(git, CONVENTION_SPEC_REFERENCE),
        eventSink = FeatureTaskRuntimeRunEventSink { event ->
          if (event is FeatureTaskRuntimeRunEvent.PhaseStarted && event.phaseId == "implement") {
            val preLaunchRecord =
              requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
            assertEquals("blocked", preLaunchRecord.status)
            assertEquals(BRANCH_SETUP_AGENT_ID, preLaunchRecord.resolvedAgentId)
            observedPreLaunchRecord = true
          }
        },
      ),
    )
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedBranchSetupBlockedPhase("implement", "checkout exploded on the prior run")

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(EXPECTED_FEATURE_BRANCH, completed.resolvedBranch)
    // The implement phase (and every later file-mutating phase) launched: the poison is cleared.
    assertEquals(AGENT_LAUNCHED_PHASES.filterNot(NON_FILE_MUTATING_PHASES::contains), harness.launchedPhaseOrder())
    // The durable record is superseded back to a completed implement-agent record, not left blocked.
    val implementRecord = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("completed", implementRecord.status)
    assertEquals(phaseAgent("implement"), implementRecord.resolvedAgentId)
    assertEquals(1, implementRecord.attemptCount)
    assertTrue(observedPreLaunchRecord, "the stale branch-setup block must remain durable until real phase launch")
    // No phase is reported blocked once setup recovers.
    val status = requireNotNull(
      FeatureTaskRuntimeStatusService(
        harness.recorder,
        harness.runInvariantsStore,
        harness.decomposeTerminalRecorder,
      )
        .status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID)),
    )
    assertEquals(0, status.blockedCount)
  }

  @Test
  fun `later file-mutating phases reattach when a prior phase leaves HEAD on the default branch`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    val launcher = RuntimeRecordingLauncher { request ->
      if (request.invokedAgentId == phaseAgent("implement")) {
        git.currentBranchValue = "main"
      }
      facts(defaultPhaseOutput(request))
    }
    val harness = runnerHarness(
      launcher = launcher,
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = conventionRuntimeConfig(git),
    )

    val report = harness.runner.run(harness.request())

    val completed = assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(EXPECTED_FEATURE_BRANCH, completed.resolvedBranch)
    assertEquals(COMPLETED_PHASES_CLEAN_RUN, harness.launchOrder())
    assertEquals(
      listOf(
        RecordingWorkflowGitOperations.CheckoutCall(EXPECTED_FEATURE_BRANCH, "main"),
        RecordingWorkflowGitOperations.CheckoutCall(EXPECTED_FEATURE_BRANCH, null),
      ),
      git.checkoutCalls,
      "review must reattach to the persisted branch after implement leaves HEAD on main",
    )
  }

  @Test
  fun `recordResolvedBranch is a non-overwriting no-op once a branch is persisted`() {
    val harness = runnerHarness()
    harness.recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    val first = FeatureTaskRuntimeResolvedBranch(branch = "feat/first-branch", baseBranch = "main", created = true)
    val divergent =
      FeatureTaskRuntimeResolvedBranch(branch = "feat/divergent-branch", baseBranch = "develop", created = false)

    assertTrue(harness.recorder.recordResolvedBranch(WORKFLOW_ID, first))
    // A second record with divergent values must be a no-op that never overwrites the first, so a
    // resume/re-run can never force a second or divergent branch for the same run.
    assertTrue(harness.recorder.recordResolvedBranch(WORKFLOW_ID, divergent))

    val persisted = requireNotNull(harness.recorder.loadResolvedBranch(WORKFLOW_ID))
    assertEquals("feat/first-branch", persisted.branch)
    assertEquals("main", persisted.baseBranch)
    assertEquals(true, persisted.created)
  }
}

class FeatureTaskRuntimeLegacyBriefingBlockTest {
  @Test
  fun `a legacy briefing row blocks the phase durably instead of unwinding the run`() {
    val harness = runnerHarness(agentAssignment = phasePerAgentAssignment())
    harness.seedPhase("preplan", "completed", 1, phaseAgent("preplan"), PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, phaseAgent("plan"), PLAN_OUTPUT)

    val legacyBriefing: Map<String, Any?> = linkedMapOf(
      "phase_id" to "plan",
      "spec_reference" to ".feature-specs/SKILL-137/spec.md",
      "feature_size" to "MEDIUM",
      "acceptance_criteria" to listOf("AC-1"),
      "mandates_and_overrides" to emptyList<String>(),
      "upstream_outputs_by_phase_id" to mapOf("preplan" to "legacy payload"),
      "derived_context_keys" to emptyList<String>(),
      "briefing_text" to "legacy briefing text",
    )
    val artifacts = LinkedHashMap(harness.repository.taskRuntimeArtifacts(WORKFLOW_ID))
    artifacts[FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY] = mapOf("plan" to legacyBriefing)
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "durable handoff envelope")
    assertContains(blocked.blockedReason, "upstream_outputs_by_phase_id")
  }
}

// SKILL-85 Subtask 3 (AC2/3/4/5/6/8): reconcile-on-resume idempotency for the now-fix-loop mutating
// implement phase, exercised through a synthetic backward edge review --needs_fix--> implement.
@Suppress("LargeClass")
class FeatureTaskRuntimeReconcileOnResumeTest {
  // (a) A mutating-phase re-run is a no-op when the tree matches target: the re-entered implement
  // returns the reconciliation report and the reconciliation gate passes, the clean worktree produces
  // no checkpoint commit, and the run converges without a duplicated mutation.
  @Test
  fun `mutating-phase re-run is a no-op when the tree matches target`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.worktreeStatusValue = "" // clean tree => no checkpoint commit
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        reviewDriver = reviewFixDriver(2),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        facts(validJsonOutput(phaseId))
      },
    )

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    // implement launched twice (initial + one reconcile re-entry); each reconciled output passed.
    assertEquals(2, harness.launchedPhaseOrder().count { it == "implement" })
    // Clean tree => no checkpoint commit on the boundary.
    assertTrue(git.createCommitMessages.isEmpty(), "a clean tree must not produce a checkpoint commit")
  }

  // (c) A dirty tree is checkpointed at every declared authority boundary: before each review and
  // before the backward edge re-enters the mutating phase. This fixture stays dirty after every
  // synthetic phase launch, so the sequence is audit, remediation, audit.
  @Test
  fun `dirty tree checkpoints review and remediation boundaries on the resolved feature branch`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        reviewDriver = reviewFixDriver(2),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        // A dirty tree the run OWNS because its writing phase produced it => one checkpoint commit
        // on the boundary.
        if (phaseId == "implement" || phaseId == "implement_fix") {
          git.worktreeStatusValue = " M src/Foo.kt"
          git.ownedPathsValue = listOf("src/Foo.kt")
        }
        facts(validJsonOutput(phaseId))
      },
    )

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    val checkpointMessages = git.createCommitMessages + git.amendCommitMessages
    assertEquals(3, checkpointMessages.size)
    assertEquals(1, git.createCommitMessages.size, "three checkpoints, one subtask commit on the branch")
    assertContains(checkpointMessages[0], "audited implementation checkpoint")
    assertContains(checkpointMessages[1], "remediation checkpoint")
    assertContains(checkpointMessages[2], "audited implementation checkpoint")
    assertTrue(checkpointMessages.all { it.contains("feat/existing-runtime-branch") })
    // The checkpoint stages its owned inventory before committing: agents never `git add`, so
    // without an explicit staging the bare commit would run against an empty index and fail (F-001).
    assertEquals(
      listOf("src/Foo.kt", "src/Foo.kt", "src/Foo.kt"),
      git.stagePathsCalls,
      "each checkpoint must stage exactly its owned inventory before committing",
    )
  }

  // F-001: a staging failure must block loudly rather than proceeding to a doomed empty-index commit.
  @Test
  fun `dirty tree checkpoint that fails to stage blocks loudly and never commits`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.stagePathsResult = WorkflowGitOperationResult(status = "error", error = "stage failed")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        reviewDriver = reviewFixDriver(2),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement" || phaseId == "implement_fix") {
          git.worktreeStatusValue = " M src/Foo.kt"
          git.ownedPathsValue = listOf("src/Foo.kt")
        }
        facts(validJsonOutput(phaseId))
      },
    )

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "stage failed")
    assertTrue(git.createCommitMessages.isEmpty(), "a failed staging must never proceed to a commit")
    assertEquals(
      1,
      git.restoreIndexStateCalls.size,
      "a failed staging must restore the pre-checkpoint index before blocking",
    )
    assertContains(blocked.blockedReason, "index was restored")
  }

  // AC-001/AC-002: the checkpoint commits its owned inventory and nothing else, whatever else is dirty.
  @Test
  fun `checkpoint stages only implementation paths while specs and foreign dirt stay alone`() {
    val git = checkpointGit(
      ownedPaths = listOf("src/Owned.kt", SPEC_REFERENCE),
      stagedPaths = listOf("unrelated/ForeignStaged.kt"),
    )
    val harness = checkpointRunHarness(git)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(git.stagePathsCalls.isNotEmpty(), "the owned inventory must be staged")
    assertEquals(
      setOf("src/Owned.kt"),
      git.stagePathsCalls.toSet(),
      "no foreign path may ever reach the staging call",
    )
  }

  // AC-001: foreign dirt alone is not this workflow's work, so it must not produce a checkpoint commit.
  @Test
  fun `only foreign dirt present produces no checkpoint commit and does not block the phase transition`() {
    val git = checkpointGit(ownedPaths = emptyList(), stagedPaths = listOf("unrelated/ForeignStaged.kt"))
    git.worktreeStatusValue = " M unrelated/ForeignStaged.kt"
    val harness = checkpointRunHarness(git)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertTrue(git.createCommitMessages.isEmpty(), "an empty owned delta must not commit foreign dirt")
    assertTrue(git.stagePathsCalls.isEmpty())
    assertTrue(git.amendCommitMessages.isEmpty(), "a Skip verdict must neither create nor amend")
    assertTrue(git.checkpointRefs.isEmpty(), "a Skip verdict must write no checkpoint ref")
  }

  // AC-005: an owned path staged outside the workflow is adopted on-branch, never a reason to refuse.
  @Test
  fun `an owned path that is also foreign-staged is committed rather than blocking the run`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"), stagedPaths = listOf("src/Owned.kt"))
    val harness = checkpointRunHarness(git)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertEquals(setOf("src/Owned.kt"), git.stagePathsCalls.toSet(), "the overlap is staged from the worktree")
    assertTrue(git.createCommitMessages.isNotEmpty(), "the checkpoint must commit rather than refuse")
  }

  // AC-006: a commit failure must leave no partial index mutation behind for a later user commit.
  @Test
  fun `a failed checkpoint commit restores the pre-checkpoint index and reports the restore outcome`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    git.indexSnapshotValue = "100644 ${"a".repeat(40)} 0\tsrc/Owned.kt"
    git.createCommitResult = WorkflowGitOperationResult(status = "error", error = "commit failed")
    val harness = checkpointRunHarness(git)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "commit failed")
    assertContains(blocked.blockedReason, "index was restored")
    assertEquals(listOf(git.indexSnapshotValue), git.restoreIndexStateCalls)
  }

  // AC-006: a restore that itself fails must loud-fail into the block reason, never continue silently.
  @Test
  fun `a restore failure is reported in the checkpoint block reason rather than swallowed`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    git.createCommitResult = WorkflowGitOperationResult(status = "error", error = "commit failed")
    git.restoreIndexStateResult = WorkflowGitOperationResult(status = "error", error = "restore failed")
    val harness = checkpointRunHarness(git)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "could NOT be restored")
    assertContains(blocked.blockedReason, "restore failed")
  }

  // AC-007/AC-008: identity survives in durable state and distinguishes each checkpoint's generation.
  @Test
  fun `every checkpoint commit records a durable identity carrying its branch phase generation and sha`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    val harness = checkpointRunHarness(git)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE)))

    val identities = requireNotNull(harness.recorder.loadCheckpointIdentities(WORKFLOW_ID))
    assertEquals(
      git.createCommitMessages.size + git.amendCommitMessages.size,
      identities.size,
      "one identity record per checkpoint, whether it created or amended the subtask commit",
    )
    assertEquals(
      identities.map { it.checkpointRef }.distinct().size,
      identities.size,
      "the ref, not the amendable commit sha, is what must be unique per checkpoint",
    )
    identities.forEach { identity ->
      assertEquals("feat/existing-runtime-branch", identity.branch)
      assertEquals(ISSUE_KEY, identity.issueKey)
      assertEquals(1, identity.ownedPathCount)
      assertTrue(identity.phaseId.isNotBlank())
      // This run carries no goal-continuation artifact, so every ref names the reserved sentinel.
      assertEquals(FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID, identity.subtaskId)
      assertEquals(
        featureTaskRuntimeCheckpointRefName(
          ISSUE_KEY,
          FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
          identity.sequenceNumber,
        ),
        identity.checkpointRef,
      )
    }
    assertTrue(
      identities.any { it.loopId != null },
      "a backward-edge checkpoint must record the loop it belongs to",
    )
  }

  // AC-001/AC-002/AC-004/AC-006: the whole defect this ceremony exists to fix. Ceremony commits used to
  // stack on the branch, one per checkpoint; now one forward checkpoint plus its remediation loops
  // leave a single commit, and every amend first preserves the commit it is about to rewrite.
  @Test
  fun `every checkpoint after the first amends one trailered subtask commit and preserves its predecessor`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    val harness = checkpointRunHarness(git)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE)))

    assertEquals(1, git.createCommitMessages.size, "one subtask, one commit on the feature branch")
    assertEquals(2, git.amendCommitMessages.size, "every later checkpoint amends instead of committing")
    (git.createCommitMessages + git.amendCommitMessages).forEach { message ->
      assertContains(message, "Skill-Bill-Subtask: $ISSUE_KEY/$FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID")
    }

    val identities = requireNotNull(harness.recorder.loadCheckpointIdentities(WORKFLOW_ID))
    assertEquals(3, identities.size)
    // Each amend wrote the commit it replaced to its own checkpoint ref, so no rewritten state was
    // discarded before it was reachable somewhere else.
    assertEquals(
      identities.drop(1).associate { it.checkpointRef to identities[it.sequenceNumber - 1].commitSha },
      git.checkpointRefs,
    )
  }

  // AC-007: a ref write that fails must stop the amend. Amending anyway discards a checkpoint state
  // nothing in the repository can reach afterwards.
  @Test
  fun `a failed pre-amend ref write blocks the checkpoint and leaves HEAD unchanged`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    git.updateCheckpointRefResult = WorkflowGitOperationResult(status = "error", error = "ref write refused")
    val harness = checkpointRunHarness(git)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "could not be preserved")
    assertContains(blocked.blockedReason, "ref write refused")
    assertTrue(git.amendCommitMessages.isEmpty(), "the amend must not run after a failed ref write")
    assertEquals(
      git.createCommitMessages.size.toString(16).padStart(40, '0'),
      git.headCommitShaValue,
      "HEAD must still be the commit the failed amend was about to rewrite",
    )
  }

  // AC-005/AC-010: process death between staging and amend can wipe the durable pointer. The trailer on
  // HEAD is what stops the resume from opening a second commit for the same subtask.
  @Test
  fun `a wiped durable pointer recovers the amend target from the HEAD trailer and records the fallback`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    val diagnostics = RecordingDiagnostics()
    var pointerWiped = false
    var harness: RunnerHarness? = null
    harness = checkpointRunHarness(
      git,
      diagnostics = diagnostics,
      // Fires once the first checkpoint has landed, modelling a crash that lost the durable pointer.
      onPhase = { phaseId ->
        if (phaseId == "audit" && !pointerWiped && git.createCommitMessages.isNotEmpty()) {
          pointerWiped = true
          harness?.recorder?.quarantineCheckpointIdentities(WORKFLOW_ID)
        }
      },
    )
    val run = requireNotNull(harness)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(run.runner.run(run.request(IMPLEMENT_FIX_CYCLE)))

    assertEquals(1, git.createCommitMessages.size, "the recovered checkpoint must not open a second commit")
    assertTrue(git.amendCommitMessages.isNotEmpty())
    assertTrue(
      diagnostics.warnings.any { it.contains("FeatureTaskRuntimeSubtaskCommitResolver.decide") },
      "the trailer fallback is a degradation and must emit an observability record",
    )
    // The quarantine restarts the checkpoint sequence, so a post-restart amend can target a ref name an
    // earlier amend already owns. Every preserved commit must still have its own ref afterwards.
    assertEquals(
      git.updateCheckpointRefCalls.size,
      git.checkpointRefs.size,
      "each amend preserves its predecessor under its own ref; a reused ref name discards a checkpoint state",
    )
    assertContains(
      git.checkpointRefs.values,
      1.toString(16).padStart(40, '0'),
      "the created subtask commit must still be reachable after the post-restart amend",
    )
  }

  // AC-007: the sequence restart can aim an amend at a ref another checkpoint already holds. Preserving is
  // the point of the ref, so the runtime refuses to move it rather than overwriting the commit it preserves.
  @Test
  fun `an amend whose checkpoint ref already preserves another commit is refused before HEAD is rewritten`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    val occupiedRef = featureTaskRuntimeCheckpointRefName(ISSUE_KEY, FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID, 1)
    val preserved = "d".repeat(40)
    git.checkpointRefs[occupiedRef] = preserved
    val harness = checkpointRunHarness(git)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "could not be preserved")
    assertContains(blocked.blockedReason, preserved)
    assertTrue(git.amendCommitMessages.isEmpty(), "the amend must not run against an occupied checkpoint ref")
    assertEquals(
      1.toString(16).padStart(40, '0'),
      git.headCommitShaValue,
      "HEAD must still be the commit the refused amend was about to rewrite",
    )
    assertEquals(preserved, git.checkpointRefs[occupiedRef], "the occupied ref must still preserve its commit")
  }

  // AC-007: a lookup that fails is not an absent ref. Treating it as free would overwrite a ref that is
  // the only reachability a preserved commit has, so undetermined occupancy must block the amend.
  @Test
  fun `an amend whose checkpoint ref occupancy cannot be determined is refused before HEAD is rewritten`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    git.resolveCheckpointRefResult = WorkflowGitOperationResult(status = "error", error = "ref lookup failed")
    val harness = checkpointRunHarness(git)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "could not be preserved")
    assertTrue(git.amendCommitMessages.isEmpty(), "the amend must not run on an undetermined checkpoint ref")
    assertTrue(git.updateCheckpointRefCalls.isEmpty(), "no ref may be written while occupancy is undetermined")
    assertEquals(
      1.toString(16).padStart(40, '0'),
      git.headCommitShaValue,
      "HEAD must still be the commit the refused amend was about to rewrite",
    )
  }

  // AC-005: the contract bump's recovery path. A store written under 0.1 must not wedge the run at its
  // next checkpoint, and must not be silently accepted either.
  @Test
  fun `a legacy checkpoint-identity store is quarantined with durable evidence and regenerated forward`() {
    val harness = checkpointRunHarness(checkpointGit(ownedPaths = listOf("src/Owned.kt")))
    harness.seedResolvedBranch("feat/existing-runtime-branch", baseBranch = "main", created = false)
    harness.seedLegacyCheckpointIdentityStore()

    val appended = harness.recorder.appendCheckpointIdentity(
      workflowId = WORKFLOW_ID,
      issueKey = ISSUE_KEY,
      subtaskId = FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
      branch = "feat/existing-runtime-branch",
      phaseId = "implement",
      loopId = null,
      generation = 0,
      parentSha = null,
      ownedPaths = listOf("src/Owned.kt"),
      commitSha = "e".repeat(40),
    )

    assertTrue(appended)
    val identities = requireNotNull(harness.recorder.loadCheckpointIdentities(WORKFLOW_ID))
    assertEquals(listOf("e".repeat(40)), identities.map { it.commitSha }, "the legacy store is reset, not merged")
    val evidence = requireNotNull(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID))
      .single { it.rejectionClass == QUARANTINE_REJECTION_CLASS_CHECKPOINT_IDENTITY_VERSION }
    assertContains(evidence.rejectionDetail, "expected=0.2")
    assertContains(evidence.rejectionDetail, "actual=0.1")
  }

  // AC-006: quarantine is scoped to the version bump alone; corruption at the current version must
  // still propagate rather than be reset away as if it were a legacy record.
  @Test
  fun `a malformed current-version checkpoint-identity store propagates instead of quarantining`() {
    val harness = checkpointRunHarness(checkpointGit(ownedPaths = listOf("src/Owned.kt")))
    harness.seedResolvedBranch("feat/existing-runtime-branch", baseBranch = "main", created = false)
    harness.seedMalformedCurrentCheckpointIdentityStore()

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      harness.recorder.appendCheckpointIdentity(
        workflowId = WORKFLOW_ID,
        issueKey = ISSUE_KEY,
        subtaskId = FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID,
        branch = "feat/existing-runtime-branch",
        phaseId = "implement",
        loopId = null,
        generation = 0,
        parentSha = null,
        ownedPaths = listOf("src/Owned.kt"),
        commitSha = "e".repeat(40),
      )
    }
    assertTrue(harness.recorder.loadQuarantinedRecords(WORKFLOW_ID).orEmpty().isEmpty())
  }
}

// The tree starts clean and the writing phase dirties it, so the phase file manifest the checkpoint
// reads shows those paths as this phase's own writes rather than as ambient pre-existing dirt.
private fun checkpointGit(
  ownedPaths: List<String>,
  stagedPaths: List<String> = emptyList(),
): RecordingWorkflowGitOperations =
  RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch").also {
    it.ownedPathsValue = ownedPaths
    it.stagedPathsValue = stagedPaths
  }

private fun checkpointRunHarness(
  git: RecordingWorkflowGitOperations,
  reviewDriver: skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver = reviewFixDriver(2),
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
  onPhase: (String) -> Unit = {},
): RunnerHarness {
  val ownedPaths = git.ownedPathsValue
  val writtenStatus = ownedPaths.joinToString("\n") { path -> " M $path" }
  git.ownedPathsValue = emptyList()
  return runnerHarness(
    agentAssignment = phasePerAgentAssignment(),
    runtimeConfig = RuntimeHarnessConfig(
      branchSetup = BranchSetupTestConfig(gitOperations = git),
      reviewDriver = reviewDriver,
    ),
    launcher = RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      if (phaseId == "implement" || phaseId == "implement_fix") {
        git.worktreeStatusValue = writtenStatus
        git.ownedPathsValue = (git.ownedPathsValue + ownedPaths).distinct()
      }
      onPhase(phaseId)
      facts(validJsonOutput(phaseId))
    },
    diagnostics = diagnostics,
  )
}

// SKILL-190: checkpoint-identity, amend and remediation-rollback behaviour on resume, split from
// FeatureTaskRuntimeReconcileOnResumeTest so neither class carries the whole reconcile surface.
class FeatureTaskRuntimeCheckpointHistoryOnResumeTest {
  // AC-002 / task-2: when the durable base record rejects the checkpoint sha, the branch soft-resets
  // to the pre-commit parent so the ref and the durable row stay paired (both unchanged).
  @Test
  fun `a failed remediation base record soft-resets the checkpoint tip back to its parent`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-base-record-rollback")
    val parentSha = COMMITTED_HEAD_SHA
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also {
        it.headCommitShaValue = parentSha
        it.invalidShaOnRemediationCommit = true
      }
    val harness = goalContinuationHarness(
      repoRoot,
      git,
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement" || phaseId == "implement_fix") {
          git.worktreeStatusValue = " M src/Foo.kt"
          git.ownedPathsValue = listOf("src/Foo.kt")
        }
        facts(validJsonOutput(phaseId))
      },
      reviewDriver = reviewFixDriver(2),
    )

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertTrue(
      blocked.blockedReason.contains("remediation") || blocked.blockedReason.contains("checkpoint"),
      blocked.blockedReason,
    )
    // The dirty tree left by `implement` earns its own audit-boundary checkpoint before the
    // remediation checkpoint is even attempted, so the failed remediation commit's parent — and the
    // soft-reset target — is that audit checkpoint's sha, not the pre-run head.
    val auditCheckpointSha = "0".repeat(39) + "1"
    assertEquals(
      listOf(auditCheckpointSha),
      git.resetSoftToCommitCalls,
      "failed paired write must soft-reset to the preceding checkpoint",
    )
    assertEquals(
      auditCheckpointSha,
      git.headCommitShaValue,
      "branch tip must match the preceding checkpoint after rollback",
    )
    assertNull(
      harness.goalContinuationRecorder.reviewState(WORKFLOW_ID)?.remediationBaseSha,
      "durable base must remain unset when the paired record failed",
    )
  }

  @Test
  fun `remediation rollback records evidence when predecessor identity commit misses`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-rollback-commit-miss")
    val parentSha = COMMITTED_HEAD_SHA
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
      .also {
        it.headCommitShaValue = parentSha
        it.invalidShaOnRemediationCommit = true
        it.onResolveCommit = { revision ->
          if (revision.trim().matches(Regex("^[0-9a-fA-F]{40,64}$")) &&
            it.createCommitMessages.any { message -> message.contains("remediation checkpoint") } &&
            revision.trim() != parentSha.trim()
          ) {
            WorkflowGitOperationResult(status = "ok", value = "")
          } else {
            null
          }
        }
      }
    val harness = goalContinuationHarness(
      repoRoot,
      git,
      RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement" || phaseId == "implement_fix") {
          git.worktreeStatusValue = " M src/Foo.kt"
          git.ownedPathsValue = listOf("src/Foo.kt")
        }
        facts(validJsonOutput(phaseId))
      },
      reviewDriver = reviewFixDriver(2),
    )

    val report = harness.runner.run(
      harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    @Suppress("UNCHECKED_CAST")
    val evidence = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)[GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY]
      as List<Map<String, Any?>>
    val entry = evidence.single { it["seam"] == "FeatureTaskRuntimeRunLoop.remediationRollbackTargetSha" }
    assertEquals("resolvable predecessor identity commit", entry["value_expected"])
    val identities = requireNotNull(harness.recorder.loadCheckpointIdentities(WORKFLOW_ID))
    val wellFormedCommitSha = Regex("^[0-9a-fA-F]{40,64}$")
    val predecessorIdentityCommitSha = identities
      .filter { it.commitSha.trim().matches(wellFormedCommitSha) }
      .maxByOrNull { it.sequenceNumber }
      ?.commitSha
      ?.trim()
    assertEquals(predecessorIdentityCommitSha, entry["value_used"])
    assertNotNull(entry["cause"])
  }

  // AC-004/AC-010: a concurrently prepared foreign spec is never staged, committed, or reviewed here.
  @Test
  fun `a concurrently prepared foreign feature spec is never staged committed or reviewed`() {
    val foreignSpec = ".feature-specs/OTHER-999-concurrent/spec_subtask_1.md"
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    // The foreign spec exists in the worktree beside this run's work but is not owned by it.
    git.ownedPathsValue = listOf("src/Owned.kt", foreignSpec)
    val harness = checkpointRunHarness(git)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    // Either outcome satisfies the contract: checkpoint only this run's paths, or block safely. What
    // is never permitted is the foreign spec being staged, committed, or entering review input.
    if (report is FeatureTaskRuntimeRunReport.Completed) {
      assertFalse(foreignSpec in git.stagePathsCalls, "a foreign issue's spec must never be staged")
      assertTrue(
        git.goalReviewBuildInputs.isNotEmpty(),
        "a completed run must have built review input for the exclusion to be proven on",
      )
    } else {
      val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
      assertContains(blocked.blockedReason, "OTHER-999")
      assertTrue(git.createCommitMessages.isEmpty())
      assertTrue(git.amendCommitMessages.isEmpty(), "a Block verdict must neither create nor amend")
      assertTrue(git.checkpointRefs.isEmpty(), "a Block verdict must write no checkpoint ref")
    }
    // Excluded either way it can be: named in the untracked exclusion list, or absent from the
    // pathspec that bounds the tracked delta. Neither disjunct is satisfiable by doing nothing.
    git.goalReviewBuildInputs.forEach { baseline ->
      assertTrue(
        foreignSpec in baseline.baselineUntrackedPaths || foreignSpec !in baseline.ownedPathspec,
        "a foreign issue's spec must be excluded from review input, never materialized into it",
      )
    }
  }

  // AC-001/AC-002: a foreign path that appears after the ownership baseline is not this run's work,
  // so being dirty must not enrol it in the inventory the checkpoint stages and commits.
  @Test
  fun `a foreign path appearing after the baseline is never staged merely because it is dirty`() {
    val foreign = "unrelated/SiblingAgentWrote.kt"
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    val harness = checkpointRunHarness(git) { phaseId ->
      // A sibling agent writes beside the run once its own writing phase is over.
      if (phaseId == "audit") git.ownedPathsValue = listOf("src/Owned.kt", foreign)
    }

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertFalse(foreign in git.stagePathsCalls, "a path this run never wrote must never be staged")
    assertTrue(git.stagePathsCalls.isNotEmpty(), "the run's own owned inventory is still checkpointed")
  }

  // AC-003: a phase with no authority to write produced a file; that blocks before any commit.
  @Test
  fun `a path introduced by a read-only phase blocks non-retryably before any commit`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    val inner = reviewFixDriver(2)
    val harness = checkpointRunHarness(
      git,
      reviewDriver = skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver { request ->
        git.ownedPathsValue = listOf("src/Owned.kt", "src/ReviewWrote.kt")
        git.worktreeStatusValue = " M src/Owned.kt\n?? src/ReviewWrote.kt"
        inner.run(request)
      },
    )

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "src/ReviewWrote.kt")
    assertFalse("src/ReviewWrote.kt" in git.stagePathsCalls, "an unowned path must never be staged")
  }

  // AC-005: an owned file edited between phases is adopted on-branch like a foreign staged entry,
  // never a reason to strand the run.
  @Test
  fun `an owned path modified concurrently after the phase wrote it is committed rather than blocking`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    val harness = checkpointRunHarness(git)
    // Someone edits the owned file after the phase stopped writing and before the checkpoint stages.
    git.onStagedPathsRead = { git.contentIdentities["src/Owned.kt"] = "edited-by-someone-else" }

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Completed>(report)
    assertContains(git.stagePathsCalls, "src/Owned.kt", "the worktree content is staged")
    assertTrue(git.createCommitMessages.isNotEmpty(), "the checkpoint must commit rather than refuse")
  }

  // AC-009: review input is limited to the owned inventory, so foreign dirt cannot enter its delta.
  @Test
  fun `review input is pathspec-limited to the workflow-owned inventory`() {
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    val harness = checkpointRunHarness(git) { phaseId ->
      if (phaseId == "audit") git.ownedPathsValue = listOf("src/Owned.kt", "unrelated/ForeignDirt.kt")
    }

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE)))

    assertTrue(git.goalReviewBuildInputs.isNotEmpty(), "review input must have been built")
    git.goalReviewBuildInputs.forEach { baseline ->
      assertTrue("src/Owned.kt" in baseline.ownedPathspec, "the owned inventory bounds the review input")
      assertFalse(
        "unrelated/ForeignDirt.kt" in baseline.ownedPathspec,
        "foreign dirt must never reach the review pathspec",
      )
    }
  }

  // AC-001/AC-009: a fix written after the first checkpoint must still reach the checkpoint commit and
  // the review pathspec. A durable inventory that only ever bootstraps would drop it from both, and the
  // second review would then pass on a delta that omits the fix.
  @Test
  fun `a file first written by implement_fix is staged and reviewed rather than dropped`() {
    val fixFile = "src/FixWrote.kt"
    var writingLaunches = 0
    val git = checkpointGit(ownedPaths = listOf("src/Owned.kt"))
    val harness = checkpointRunHarness(git) { phaseId ->
      // The remediation re-entry of the writing phase, i.e. the fix pass after review asked for one.
      if ((phaseId == "implement" || phaseId == "implement_fix") && ++writingLaunches == 2) {
        git.ownedPathsValue = listOf("src/Owned.kt", fixFile)
        git.worktreeStatusValue = " M src/Owned.kt\n?? $fixFile"
      }
    }

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE)))

    assertTrue(fixFile in git.stagePathsCalls, "the fix's new file must be staged by the next checkpoint")
    assertTrue(
      git.goalReviewBuildInputs.any { fixFile in it.ownedPathspec },
      "the fix's new file must be inside the delta the remediation review judges",
    )
  }

  // F-002: the runner's protected-branch checkpoint guard is belt-and-suspenders — branch setup is the
  // upstream gatekeeper that blocks a protected resolved branch before any mutating phase launches, so
  // a dirty tree with a protected resolved branch produces no checkpoint commit (the boundary is never
  // reached). Seeding a protected persisted branch with HEAD already on it drives that state.
  @Test
  fun `dirty tree with a protected resolved branch never checkpoints`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "main")
    git.worktreeStatusValue = " M src/Foo.kt"
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = conventionRuntimeConfig(git),
    )
    harness.seedResolvedBranch("main", baseBranch = "main", created = false)

    val report = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertTrue(
      git.createCommitMessages.isEmpty(),
      "a protected resolved branch must never produce a checkpoint commit even on a dirty tree",
    )
  }

  // F-004: suppress_pr changes the terminal push behavior, not checkpoint authority. An always-dirty
  // goal-continuation run therefore commits both audit-review boundaries and its remediation boundary.
  // WorkflowGitOperations exposes no push, so this path cannot publish any of those commits.
  @Test
  fun `suppress_pr goal-continuation checkpoints every authority boundary and never pushes`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-goalcont-checkpoint")
    val specPath = repoRoot.resolve(SPEC_REFERENCE)
    Files.createDirectories(specPath.parent)
    Files.writeString(specPath, "---\nstatus: Pending\nspec_source: linear\n---\n\n# Spec\n")
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        repoRoot = repoRoot,
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
        reviewDriver = reviewFixDriver(2),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement" || phaseId == "implement_fix") {
          git.worktreeStatusValue = " M src/Foo.kt"
          git.ownedPathsValue = listOf("src/Foo.kt")
        }
        facts(validJsonOutput(phaseId))
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))
    val checkpointMessages = git.createCommitMessages + git.amendCommitMessages
    assertEquals(
      3,
      checkpointMessages.size,
      "suppress_pr must checkpoint review and remediation authority boundaries",
    )
    assertEquals(1, git.createCommitMessages.size, "three checkpoints, one subtask commit on the branch")
    assertContains(checkpointMessages[0], "audited implementation checkpoint")
    assertContains(checkpointMessages[1], "remediation checkpoint")
    assertContains(checkpointMessages[2], "finalised subtask checkpoint")
  }

  // (c continued) A checkpoint is never created on the default branch: a non-mutating cycle (no
  // mutating phase re-entered) never reaches the checkpoint boundary even on a dirty tree.
  @Test
  fun `non-mutating cycle never checkpoints even on a dirty tree`() {
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    git.worktreeStatusValue = " M src/Foo.kt"
    var planLaunches = 0
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "plan") {
          planLaunches += 1
          facts(verdictPlanOutput(if (planLaunches == 1) "needs_fix" else "advance"))
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request(PLAN_FIX_CYCLE)))
    assertTrue(git.createCommitMessages.isEmpty(), "a non-mutating cycle must never reach the checkpoint boundary")
  }

  // (d) The reconciliation gate rejects an implement output that did not report reconciliation: the
  // silent skip is routed through the loud schema-gate failure path, so implement retries until a
  // reconciled receipt lands. A reconciled output advances (proved in test (a)).
  @Test
  fun `reconciliation gate rejects an implement output without a reconciliation report`() {
    var implementLaunches = 0
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement") {
          implementLaunches += 1
          facts(if (implementLaunches == 1) IMPLEMENT_NO_RECONCILE_OUTPUT else validJsonOutput(phaseId))
        } else {
          facts(validJsonOutput(phaseId))
        }
      },
    )

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertEquals(1, implementLaunches, "a one-attempt budget leaves no relaunch")
    assertGateBlockNamesRule(blocked.blockedReason, "mutating-reconciliation")
    assertDiagnosticNamesConstraint(
      harness.io.database.rejectedDiagnostics().first { it.metadata.phaseId == "implement" }.metadata.reason,
      "reconciliation report",
    )
  }

  // (b) A simulated mid-implement crash then a clean resume reconciles to target without double-apply,
  // and WITHOUT resetting the per-edge cap counter: the surviving edge watermark means the resumed
  // re-entry mints the NEXT edge iteration, so across both runs the edge fires exactly 1..cap.
  @Test
  fun `crash mid-implement resume reconciles without resetting the per-edge cap`() {
    var implementLaunches = 0
    var crashOnReentry = true
    val git = RecordingWorkflowGitOperations(currentBranchValue = "feat/existing-runtime-branch")
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = BranchSetupTestConfig(gitOperations = git),
        reviewDriver = reviewFixDriver(Int.MAX_VALUE),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        when {
          phaseId == "implement" && ++implementLaunches == 2 && crashOnReentry -> spawnFailedFacts()
          else -> facts(validJsonOutput(phaseId))
        }
      },
    )

    // Run 1: implement -> review needs_fix fires the edge (iteration 1) -> implement re-enters & crashes.
    val firstReport = harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE))
    val firstBlocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(firstReport)
    assertEquals("implement", firstBlocked.lastIncompletePhase)
    // The terminal blocked record retained the loop context — the watermark a reset would drop.
    val blockedImplement = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["implement"])
    assertEquals("blocked", blockedImplement.status)
    assertEquals("implement-fix", blockedImplement.loopId)
    assertEquals(1, blockedImplement.edgeIteration)

    // Run 2 (resume): the crash heals. The surviving watermark means the resume mints the NEXT edge
    // iteration (the cap), never restarting at 1, so across both runs the edge fires exactly 1..cap.
    crashOnReentry = false
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request(IMPLEMENT_FIX_CYCLE)))
    val edgeIterations = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
      .filter { it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE }
      .mapNotNull { it.edgeIteration }
    assertEquals(
      (1..IMPLEMENT_FIX_CAP).toList(),
      edgeIterations,
      "the edge fired 1..cap across the crash, never restarting the per-edge counter",
    )
  }

  // (e) Regression guard: removing the implement exclusion does NOT regress same-phase schema-retry
  // bounds for NON-mutating phases. A non-fix-loop phase still blocks immediately on a schema-invalid
  // output without retrying.
  @Test
  fun `non-mutating non-fix-loop phase still blocks immediately on schema-invalid output`() {
    val harness = runnerHarness(
      validator = ThrowingValidator(failPhases = setOf("write_history")),
      agentAssignment = phasePerAgentAssignment(),
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertEquals("write_history", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "does not participate in a fix loop")
    assertEquals(1, harness.launchedPhaseOrder().count { it == "write_history" })
  }
}

internal const val WORKFLOW_ID = "wftr-20260602-test-0001"
internal const val SESSION_ID = "ftr-test-001"
private const val ISSUE_KEY = "SKILL-65"
private const val SPEC_REFERENCE = ".feature-specs/SKILL-65/spec.md"

// A spec whose parent directory follows the `{ISSUE_KEY}-{feature-name}` convention so the
// derived feature branch is `feat/{ISSUE_KEY}-{feature-name}` (GoalRunnerTest-style assertion).
private const val CONVENTION_SPEC_REFERENCE =
  ".feature-specs/SKILL-65-runtime-feature-task-parity/spec_subtask_1.md"
private const val EXPECTED_FEATURE_BRANCH = "feat/SKILL-65-runtime-feature-task-parity"
internal const val INVOKED_AGENT = "claude-code"
internal const val VALID_OUTPUT = """{"contract_version":"0.2"}"""

// Gate-repair segment: schema-valid completed validate without measured gate_run_count/gate_runs.
// The consumer-projection gate must not reject this; only runtimeOwnedValidationOutput may publish counts.
private val VALIDATE_REPAIR_WITHOUT_GATE_COUNTS = """
  {
    "contract_version": "0.3",
    "phase_id": "validate",
    "status": "completed",
    "summary": "Gate repair segment without measured counts.",
    "produced_outputs": {
      "validation_result": {
        "validation_status": "passed",
        "checks": [{"name": "check", "status": "passed"}],
        "repository_checkpoint": {"fingerprint": "fixture-checkpoint-1"}
      },
      "validation_repair_plan": [
        { "identities": ["app|t|broken|A.kt"] }
      ],
      "substantiation_receipts": [
        {
          "identity": "app|t|broken|A.kt",
          "root_cause": "fixture compile failure",
          "changed_paths_or_symbols": ["A.kt"],
          "rationale": "repaired for coverage"
        }
      ]
    }
  }
""".trimIndent()

/** Fail once, then pass — drives the validate repair loop without inventing gate_run_count. */
private fun failThenPassValidationGateRunner(
  gateCalls: java.util.concurrent.atomic.AtomicInteger,
): skillbill.ports.validation.ValidationGateRunner = object : skillbill.ports.validation.ValidationGateRunner {
  override fun run(
    request: skillbill.ports.validation.model.ValidationGateRunRequest,
  ): skillbill.ports.validation.model.ValidationGateRunResult {
    val call = gateCalls.getAndIncrement()
    val outcome = if (call == 0) {
      skillbill.ports.validation.model.ValidationGateRunOutcome.FAILED
    } else {
      skillbill.ports.validation.model.ValidationGateRunOutcome.PASSED
    }
    return skillbill.ports.validation.model.ValidationGateRunResult(
      exitCode = if (call == 0) 1 else 0,
      durationMs = 1,
      outcome = outcome,
      cacheMode = if (call == 0) {
        skillbill.ports.validation.model.ValidationGateCacheMode.CACHE_ELIGIBLE
      } else {
        request.cacheMode
      },
      executedWorkUnits = 1,
      findings = if (call == 0) {
        listOf(
          skillbill.ports.validation.model.ValidationGateFinding("app", "t", "broken", "A.kt"),
        )
      } else {
        emptyList()
      },
    )
  }
}

private fun kotlinPackWithValidationGate(): skillbill.scaffold.model.PlatformManifest =
  skillbill.scaffold.model.PlatformManifest(
    slug = "kotlin",
    packRoot = Path.of("/tmp/repo/platform-packs/kotlin"),
    contractVersion = "1.7",
    routingSignals = skillbill.scaffold.model.RoutingSignals(
      strong = listOf("src"),
      tieBreakers = emptyList(),
      path = listOf("src"),
    ),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles = skillbill.scaffold.model.DeclaredFiles(null, emptyMap()),
    areaMetadata = emptyMap(),
    validationGate = skillbill.scaffold.model.ValidationGateDeclaration(
      fullGateCommand = listOf("echo", "cache"),
      cacheBypassingFullGateCommand = listOf("echo", "full"),
      collectAllFullGateCommand = listOf("echo", "collect-all"),
      cacheBypassingCollectAllFullGateCommand = listOf("echo", "collect-all-full"),
      findings = skillbill.scaffold.model.ValidationGateFindingsLocator(
        format = skillbill.scaffold.model.ValidationGateFindingsFormat.JUNIT_XML,
        artifactGlobs = listOf("**/*.xml"),
        compilerDiagnostics = skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator(
          skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT,
        ),
        executedWork = skillbill.scaffold.model.ValidationGateExecutedWorkSignal(
          skillbill.scaffold.model.ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY,
        ),
      ),
    ),
  )

// A clean review output carrying an empty findings array (the affirmative "no blocking findings"
// signal the review gate requires, SKILL-85 Subtask 4 F-003); used by the default phase-aware launcher.
private const val VALID_REVIEW_OUTPUT = """{"contract_version":"0.3","produced_outputs":{"findings":[]}}"""

private const val VALID_AUDIT_OUTPUT =
  """{"contract_version":"0.4","verdict":"satisfied","produced_outputs":{"gaps":[]}}"""

private val VALID_VERIFY_FINDINGS_OUTPUT = verifyFindingsOutput()

// preplan, plan, and implement feed the bounded planning projections, so their seeded outputs are
// full envelopes carrying the declared projection body rather than bare produced_outputs fragments.
private val PREPLAN_OUTPUT = seededProjectionEnvelope("preplan", PlanningProjectionFixtures.PREPLAN_DIGEST)
private val PLAN_OUTPUT = seededProjectionEnvelope("plan", PlanningProjectionFixtures.EXECUTABLE_PLAN)
internal val IMPLEMENT_OUTPUT =
  seededProjectionEnvelope("implement", PlanningProjectionFixtures.IMPLEMENTATION_RECEIPT)

private fun seededProjectionEnvelope(phaseId: String, producedOutputs: String): String =
  """{"contract_version":"0.3","phase_id":"$phaseId","status":"completed",""" +
    """"summary":"Phase produced a validated output.","produced_outputs":$producedOutputs}"""

internal val ALL_PHASES =
  listOf(
    "preplan",
    "plan",
    "implement",
    "audit",
    "review",
    "verify_findings",
    "implement_fix",
    "build",
    "validate",
    "write_history",
    "commit_push",
    "pr",
  )
internal val COMPLETED_PHASES_CLEAN_RUN = ALL_PHASES.filterNot { it == "implement_fix" || it == "build" }
internal val AGENT_LAUNCHED_PHASES = ALL_PHASES.filterNot { it == "review" || it == "implement_fix" || it == "build" }
private val NON_FILE_MUTATING_PHASES = setOf("preplan", "plan")

// A worker lease a killed child left behind, already expired relative to the startup reconcile pass.
private fun expiredCrashedOwnership(): FeatureTaskRuntimeWorkerOwnership = FeatureTaskRuntimeWorkerOwnership(
  workflowId = WORKFLOW_ID,
  generation = 1,
  ownerToken = "crashed-child-token",
  hostIdentity = "harness-host",
  bootIdentity = "harness-boot",
  pid = 7,
  processBirthToken = "harness-birth-7",
  leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
  heartbeatAt = "2000-01-01T00:00:00Z",
  expiresAt = "2000-01-01T00:00:30Z",
  phaseId = "implement",
  phaseAttempt = 1,
)

// A distinct invoking agent per phase so a captured launch request is
// phase-attributable from its invokedAgentId.
internal fun phaseAgent(phaseId: String): String = "agent-$phaseId"

internal fun phasePerAgentAssignment(): FeatureTaskRuntimeAgentAssignment =
  FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = ALL_PHASES.associateWith(::phaseAgent))

// Bundles the persistence + git collaborators a harness exposes so the harness constructor stays
// within the parameter budget.
internal class RunnerHarnessIo(
  val workflow: RunnerHarnessWorkflow,
  val repository: InMemoryRuntimeWorkflowRepository,
  val gitOperations: RecordingWorkflowGitOperations,
  val specStatusWriter: RecordingSpecStatusWriter,
  val database: RuntimeFakeDatabaseSessionFactory,
)

internal class RunnerHarnessWorkflow(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
)

internal class RunnerHarness(
  val launcher: RuntimeRecordingLauncher,
  val io: RunnerHarnessIo,
  val runner: FeatureTaskRuntimeRunner,
  val events: MutableList<FeatureTaskRuntimeRunEvent>,
  private val runRequest: FeatureTaskRuntimeRunRequest,
  val specScratchStore: RecordingSpecScratchStore,
) {
  val specStatusWriter: RecordingSpecStatusWriter get() = io.specStatusWriter
  val recorder: FeatureTaskRuntimePhaseRecorder get() = io.workflow.recorder
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder
    get() = io.workflow.goalContinuationRecorder
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder
    get() = io.workflow.decomposeTerminalRecorder
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore get() = io.workflow.runInvariantsStore
  val repository: InMemoryRuntimeWorkflowRepository get() = io.repository
  val gitOperations: RecordingWorkflowGitOperations get() = io.gitOperations
  val ledgerRows: List<skillbill.goalrunner.model.UnaddressedFinding> get() = io.database.ledgerRows

  // Direct review-state seeding via updateReviewState only patches goal_subtask_review_state; the
  // decoder also requires a durable raw result for every completed pass, so fixtures that
  // hand-assemble completed passes must patch this sibling artifact themselves.
  fun seedRawReviewResults(state: GoalSubtaskReviewState) {
    val artifacts = repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY] = state.passResults.associate { result ->
      result.passNumber.toString() to "raw review result for pass ${result.passNumber}"
    }
    repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }

  fun reviewedDeltaDigest(): String? =
    requireNotNull(goalContinuationRecorder.reviewState(WORKFLOW_ID)).reviewedDeltaDigest

  fun currentReviewDeltaDigest(git: RecordingWorkflowGitOperations, repoRoot: Path): String {
    val state = requireNotNull(goalContinuationRecorder.reviewState(WORKFLOW_ID))
    return requireNotNull(
      git.buildGoalSubtaskReviewInput(
        repoRoot,
        GoalSubtaskReviewBaseline(state.reviewBaseSha, state.baselineUntrackedPaths),
        "feat/existing-runtime-branch",
      ).input,
    ).deltaDigest
  }

  // Rewrites the durable review state as a pre-digest writer left it, so the legacy-cap path is
  // exercised against a real record rather than a hand-built one.
  fun stripReviewedDeltaDigest() {
    val artifacts = repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    val state = skillbill.contracts.JsonSupport
      .anyToStringAnyMap(artifacts[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY])
      .orEmpty()
      .toMutableMap()
    state.remove("reviewed_delta_digest")
    artifacts[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY] = state
    repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }

  // Launch order recovered from the event stream: each launch is preceded by a
  // PhaseStarted or a PhaseFixLoopIteration carrying the phase id.
  fun launchOrder(): List<String> = events.mapNotNull { event ->
    when (event) {
      is FeatureTaskRuntimeRunEvent.PhaseStarted -> event.phaseId
      is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration -> event.phaseId
      else -> null
    }
  }

  // Launch order derived from the launcher's captured requests; requires
  // phasePerAgentAssignment so each request's invokedAgentId maps back to its phase.
  fun launchedPhaseOrder(): List<String> = launcher.requests.map { request ->
    ALL_PHASES.firstOrNull { phaseId -> phaseAgent(phaseId) == request.invokedAgentId }
      ?: error("Launch request agent '${request.invokedAgentId}' is not phase-attributable.")
  }

  // Launch order parsed from each captured request's prompt header, so it covers phases outside
  // ALL_PHASES (e.g. the loop-only implement_fix) that launchedPhaseOrder cannot attribute by agent.
  fun launchedPromptPhaseOrder(): List<String> = launcher.requests.map { request ->
    phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
  }

  // Ensures the runtime workflow row exists first: the recorder write seam is a
  // no-op against a missing row.
  fun seedPhase(phaseId: String, status: String, attemptCount: Int, agentId: String, outputArtifact: String?) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseStateForTest(phaseId, status, attemptCount, agentId, outputArtifact)
  }

  fun seedReviewPhase(status: String, attemptCount: Int, outputArtifact: String?, reviewPassNumber: Int) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        status = status,
        attemptCount = attemptCount,
        resolvedAgentId = phaseAgent("review"),
        finished = status == "completed",
        outputArtifact = outputArtifact,
        reviewPassNumber = reviewPassNumber,
      ),
    )
  }

  // Seeds a checkpoint-identity store at the superseded 0.1 contract version.
  fun seedLegacyCheckpointIdentityStore() {
    seedCheckpointIdentityStore(
      mapOf(
        "contract_version" to "0.1",
        "checkpoints" to listOf(
          mapOf(
            "sequence_number" to 0,
            "issue_key" to ISSUE_KEY,
            "branch" to "feat/existing-runtime-branch",
            "phase_id" to "implement",
            "generation" to 0,
            "owned_path_digest" to "a".repeat(64),
            "owned_path_count" to 1,
            "commit_sha" to "b".repeat(40),
            "recorded_at" to "2026-08-10T00:00:00Z",
          ),
        ),
      ),
    )
  }

  // Seeds a store at the CURRENT version whose entry is corrupt, which is not a quarantine trigger.
  fun seedMalformedCurrentCheckpointIdentityStore() {
    seedCheckpointIdentityStore(
      mapOf(
        "contract_version" to
          skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
        "checkpoints" to listOf(mapOf("sequence_number" to 0)),
      ),
    )
  }

  private fun seedCheckpointIdentityStore(store: Map<String, Any?>) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    repository.replaceTaskRuntimeArtifacts(
      WORKFLOW_ID,
      LinkedHashMap(repository.taskRuntimeArtifacts(WORKFLOW_ID)).apply {
        put(FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY, store)
      },
    )
  }

  // Seeds a foreign-mode (prose) row at WORKFLOW_ID for the runtime mode-collision path.
  fun seedProseModeWorkflow() {
    repository.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = WORKFLOW_ID,
        sessionId = SESSION_ID,
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = "running",
        currentStepId = "implement",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = skillbill.ports.persistence.model.FeatureTaskWorkflowMode.PROSE,
      ),
      skillbill.ports.persistence.model.FeatureTaskWorkflowMode.PROSE,
    )
  }

  // Seeds the durable run-scoped resolved branch, simulating a prior run that already established it.
  fun seedResolvedBranch(branch: String, baseBranch: String?, created: Boolean) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordResolvedBranch(
      WORKFLOW_ID,
      skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch(
        branch = branch,
        baseBranch = baseBranch,
        created = created,
        reviewBaseSha = "0".repeat(40),
      ),
    )
  }

  // Seeds a durable terminal blocked per-phase record (the marker that survives ledger pruning).
  fun seedBlockedPhase(
    phaseId: String,
    attemptCount: Int,
    agentId: String,
    blockedReason: String,
    failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = "blocked",
        attemptCount = attemptCount,
        resolvedAgentId = agentId,
        finished = false,
        outputArtifact = null,
        blockedReason = blockedReason,
        failureDisposition = failureDisposition,
      ),
    )
  }

  // Seeds a durable per-phase record carrying backward-edge loop context (loop id + per-edge
  // iteration), modelling a prior run that re-entered this phase through a backward edge.
  @Suppress("LongParameterList") // mirrors the full seeded per-phase record surface
  fun seedReentryPhase(
    phaseId: String,
    status: String,
    attemptCount: Int,
    agentId: String,
    outputArtifact: String?,
    loopId: String,
    edgeIteration: Int,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = status,
        attemptCount = attemptCount,
        resolvedAgentId = agentId,
        finished = status == "completed",
        outputArtifact = outputArtifact,
        loopId = loopId,
        edgeIteration = edgeIteration,
      ),
    )
  }

  fun seedLoopEdge(phaseId: String, loopId: String, edgeIteration: Int) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.appendLedgerEntry(
      skillbill.application.model.FeatureTaskRuntimePhaseLedgerRequest(
        workflowId = WORKFLOW_ID,
        action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
        phaseId = phaseId,
        attemptCount = edgeIteration,
        resolvedAgentId = INVOKED_AGENT,
        loopId = loopId,
        edgeIteration = edgeIteration,
      ),
    )
  }

  // Seeds a durable branch-setup-origin blocked record (keyed to the branch-setup sentinel agent),
  // modelling a prior run that blocked while establishing the feature branch for the phase.
  fun seedBranchSetupBlockedPhase(phaseId: String, blockedReason: String) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = phaseId,
        status = "blocked",
        attemptCount = 1,
        resolvedAgentId = BRANCH_SETUP_AGENT_ID,
        finished = false,
        outputArtifact = null,
        blockedReason = blockedReason,
      ),
    )
  }

  fun request(): FeatureTaskRuntimeRunRequest = runRequest

  // Drives the run with a synthetic cyclic transition declaration (the test-only inert seam).
  fun request(
    transitionsOverride: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration,
  ): FeatureTaskRuntimeRunRequest = runRequest.copy(transitionsOverride = transitionsOverride)
}

// Mirrors the runner's branch-setup sentinel agent id so tests can seed a branch-setup-origin block.
private const val BRANCH_SETUP_AGENT_ID = "branch-setup"

// Bundles the branch-setup-relevant test inputs so runnerHarness stays within the parameter budget.
// The review_fix edge records HEAD as the pre-fix remediation base, so goal-continuation fixtures
// model a repository that has commits.
// Review returns a Blocker for the first two passes, then approves; every remediation pass carries the
// evidenced disposition the parse seam requires for the prior pass's Blocker.
// Pass one raises a Blocker, pass two still finds it unresolved and raises its own, pass three clears
// it. Every remediation pass dispositions the Blocker ids of its IMMEDIATELY PRECEDING pass — the ids
// the parse seam mints from that pass's durable result — not pass one's forever.
//
// The first implement_fix mutates the reviewed delta so non-convergence does not pause after that
// tree-changing fix; later fix passes leave the digest stable.
private fun remediationReviewLauncher(git: RecordingWorkflowGitOperations): RuntimeRecordingLauncher =
  RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    if (phaseId == "implement_fix" && git.goalReviewTrackedDelta.isEmpty()) {
      git.goalReviewTrackedDelta = "remediation-progress\n"
    }
    facts(validJsonOutput(phaseId))
  }

private const val COMMITTED_HEAD_SHA = "ffffffffffffffffffffffffffffffffffffffff"

private fun committedRepoBranchSetup(): BranchSetupTestConfig = BranchSetupTestConfig(
  gitOperations = RecordingWorkflowGitOperations().also { it.headCommitShaValue = COMMITTED_HEAD_SHA },
)

internal data class BranchSetupTestConfig(
  val gitOperations: RecordingWorkflowGitOperations = RecordingWorkflowGitOperations(),
  val specReference: String = SPEC_REFERENCE,
  val featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
)

internal data class RuntimeHarnessConfig(
  val branchSetup: BranchSetupTestConfig = BranchSetupTestConfig(),
  val repoRoot: Path = Path.of("/tmp/repo"),
  val environment: Map<String, String> = emptyMap(),
  val goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null,
  val useRealDecompositionPlanner: Boolean = false,
  val eventSink: FeatureTaskRuntimeRunEventSink? = null,
  val dbPathOverride: String? = null,
  val acceptanceCriteria: List<String> = listOf("AC-1", "AC-2"),
  // SKILL-140 Subtask 3 (AC-004): the shared harness default leaves the canonical schema unenforced so
  // runner-behavior tests stay focused on run-loop flow. Schema-gate/seam behavior is proven against the
  // real validator by the RealValidator* integration suites, which pass this override. The permitted-Noop
  // allow-list is pinned by PlanningProjectionNoopValidatorGuardTest.
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator =
    NoopFeatureTaskRuntimePlanningProjectionValidator,
  val buildReceiptValidator: FeatureTaskRuntimeBuildReceiptValidator =
    NoopFeatureTaskRuntimeBuildReceiptValidator,
  val parallelReviewAgent: String? = null,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val sharedEvidenceResolver: skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort =
    skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort.NONE,
  val diffResolver: skillbill.ports.diff.DiffResolverPort = object : skillbill.ports.diff.DiffResolverPort {
    override fun runProcess(args: List<String>, workDir: java.nio.file.Path): String? = null
  },
  val validationGateRunner: skillbill.ports.validation.ValidationGateRunner? = null,
  val validationGatePlatformManifests: List<skillbill.scaffold.model.PlatformManifest> = emptyList(),
  val reviewDriver: skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver =
    skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY,
)

private fun runtimeSpecSourceResolver(): SpecSourceResolver =
  SpecSourceResolver(TestDecompositionManifestFileStore, testDecompositionManifestValidator)

@Suppress("LongParameterList") // mirrors FeatureTaskRuntimePhaseGates' own constructor arity
private fun runtimePhaseGates(
  branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  planningStopper: FeatureTaskRuntimePlanningStopper,
  lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  specGate: FeatureTaskRuntimeSpecGate = testSpecGate(),
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator =
    NoopFeatureTaskRuntimePlanningProjectionValidator,
  buildReceiptValidator: FeatureTaskRuntimeBuildReceiptValidator =
    NoopFeatureTaskRuntimeBuildReceiptValidator,
  sharedEvidenceResolver: skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort =
    skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort.NONE,
  diffResolver: skillbill.ports.diff.DiffResolverPort = object : skillbill.ports.diff.DiffResolverPort {
    override fun runProcess(args: List<String>, workDir: java.nio.file.Path): String? = null
  },
  recorder: FeatureTaskRuntimePhaseRecorder,
  validationGateRunnerOverride: skillbill.ports.validation.ValidationGateRunner? = null,
  validationGatePlatformManifests: List<skillbill.scaffold.model.PlatformManifest> = emptyList(),
  reviewDriver: skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver =
    skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY,
): FeatureTaskRuntimePhaseGates {
  val validationGateResolver =
    skillbill.application.featuretask.validation.ValidationGateResolver { validationGatePlatformManifests }
  val validationGateRunner = validationGateRunnerOverride
    ?: object : skillbill.ports.validation.ValidationGateRunner {
      override fun run(request: skillbill.ports.validation.model.ValidationGateRunRequest) =
        skillbill.ports.validation.model.ValidationGateRunResult(
          exitCode = 0,
          durationMs = 1,
          outcome = skillbill.ports.validation.model.ValidationGateRunOutcome.PASSED,
          cacheMode = request.cacheMode,
          executedWorkUnits = 1,
          findings = emptyList(),
        )
    }
  return FeatureTaskRuntimePhaseGates(
    branchSetupRunner,
    planningStopper,
    lifecycleTelemetry,
    gitOperations,
    specGate,
    planningProjectionValidator,
    buildReceiptValidator,
    validationGateResolver,
    validationGateRunner,
    skillbill.application.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator(
      validationGateResolver,
      validationGateRunner,
      skillbill.application.featuretask.validation.FeatureTaskRuntimeValidationGateProgressStore(recorder),
      defaultRepoLocalConfigPort(),
    ),
    skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator(
      validationGateResolver,
      validationGateRunner,
      skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateProgressStore(recorder),
      defaultRepoLocalConfigPort(),
    ),
    sharedEvidenceResolver,
    diffResolver,
    reviewDriver,
    SpecIntentProjectionResolver(
      TestDecompositionManifestFileStore,
      testDecompositionManifestValidator,
      SpecIntentProjectionExtractor(
        ReviewContextEnvelopeValidator { _, _ -> },
        TestDecompositionManifestFileStore,
      ),
    ),
    FeatureTaskRuntimeFindingVerificationBoundaryMemory(
      skillbill.goalplanning.FileSystemGoalPlanningContextDiscovery(),
      skillbill.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver(),
    ),
  )
}

private fun defaultRepoLocalConfigPort(): skillbill.ports.config.RepoLocalConfigPort =
  object : skillbill.ports.config.RepoLocalConfigPort {
    override fun readRepoLocalConfig(request: skillbill.ports.config.model.ReadRepoLocalConfigRequest) =
      skillbill.ports.config.model.ReadRepoLocalConfigResult(skillbill.config.model.RepoLocalConfig.defaults())
  }

private fun testSpecGate(
  specScratchStore: SpecScratchStore = RecordingSpecScratchStore(),
  specStatusWriter: FeatureTaskRuntimeSpecStatusWriter = RecordingSpecStatusWriter(),
): FeatureTaskRuntimeSpecGate =
  FeatureTaskRuntimeSpecGate(runtimeSpecSourceResolver(), specScratchStore, specStatusWriter)

private fun disabledRuntimeLifecycleTelemetry(database: DatabaseSessionFactory): FeatureTaskRuntimeLifecycleTelemetry =
  FeatureTaskRuntimeLifecycleTelemetry(
    LifecycleTelemetryService(database, DisabledRuntimeTelemetrySettingsProvider),
  )

private object DisabledRuntimeTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = TelemetrySettings(
    configPath = Path.of("/fake/config.json"),
    level = "off",
    enabled = false,
    installId = "",
    proxyUrl = "",
    customProxyUrl = null,
    batchSize = 50,
  )
}

private fun smallRuntimeConfig(): RuntimeHarnessConfig = RuntimeHarnessConfig(
  branchSetup = BranchSetupTestConfig(featureSize = FeatureTaskRuntimeFeatureSize.SMALL),
)

private fun conventionRuntimeConfig(git: RecordingWorkflowGitOperations): RuntimeHarnessConfig =
  RuntimeHarnessConfig(branchSetup = BranchSetupTestConfig(git, CONVENTION_SPEC_REFERENCE))

private fun runnerHarnessRequest(
  runtimeConfig: RuntimeHarnessConfig,
  agentAssignment: FeatureTaskRuntimeAgentAssignment,
  sink: FeatureTaskRuntimeRunEventSink,
): FeatureTaskRuntimeRunRequest = FeatureTaskRuntimeRunRequest(
  issueKey = ISSUE_KEY,
  workflowId = WORKFLOW_ID,
  sessionId = SESSION_ID,
  runInvariants = FeatureTaskRuntimeRunInvariants(
    specReference = runtimeConfig.branchSetup.specReference,
    featureSize = runtimeConfig.branchSetup.featureSize,
    acceptanceCriteria = runtimeConfig.acceptanceCriteria,
    mandatesAndOverrides = listOf("mandate-X"),
    codeReviewMode = runtimeConfig.codeReviewMode,
  ),
  invokedAgentId = INVOKED_AGENT,
  agentAssignment = agentAssignment,
  environment = runtimeConfig.environment,
  dbPathOverride = null,
  repoRoot = runtimeConfig.repoRoot,
  goalContinuation = runtimeConfig.goalContinuation,
  parallelReviewAgent = runtimeConfig.parallelReviewAgent,
  eventSink = sink,
)

@Suppress("LongParameterList") // test factory; named overrides are clearer than a config bag here
internal fun runnerHarness(
  launcher: RuntimeRecordingLauncher = defaultPhaseAwareLauncher(),
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
  runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
  repository: InMemoryRuntimeWorkflowRepository = InMemoryRuntimeWorkflowRepository(),
  crashSupervisor: FeatureTaskRuntimeWorkerSupervisor = HarnessDeadProcessSupervisor,
  diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
): RunnerHarness {
  harnessPendingVerifyFindingIds = emptyList()
  seedHarnessSpecIntentProjection(runtimeConfig.repoRoot, runtimeConfig.branchSetup.specReference)
  val specScratchStore = RecordingSpecScratchStore()
  val specStatusWriter = RecordingSpecStatusWriter()
  val database = RuntimeFakeDatabaseSessionFactory(repository)
  val recorder = FeatureTaskRuntimePhaseRecorder(
    database,
    NoopWorkflowSnapshotValidator,
    AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
    AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
  )
  val goalContinuationRecorder = FeatureTaskRuntimeGoalContinuationRecorder(database, NoopWorkflowSnapshotValidator)
  val decomposeTerminalRecorder =
    FeatureTaskRuntimeDecomposeTerminalRecorder(database, NoopWorkflowSnapshotValidator)
  val runInvariantsStore = FeatureTaskRuntimeRunInvariantsStore(database, NoopWorkflowSnapshotValidator)
  val runner = harnessRunner(
    launcher, recorder, goalContinuationRecorder, runInvariantsStore, validator,
    runtimeConfig, database, crashSupervisor, diagnostics, specScratchStore, specStatusWriter,
    decomposeTerminalRecorder,
  )
  val captured = mutableListOf<FeatureTaskRuntimeRunEvent>()
  val sink = FeatureTaskRuntimeRunEventSink { event ->
    captured += event
    runtimeConfig.eventSink?.emit(event)
  }
  val runRequest = runnerHarnessRequest(runtimeConfig, agentAssignment, sink)
  val io = RunnerHarnessIo(
    workflow = RunnerHarnessWorkflow(
      recorder,
      goalContinuationRecorder,
      decomposeTerminalRecorder,
      runInvariantsStore,
    ),
    repository = repository,
    gitOperations = runtimeConfig.branchSetup.gitOperations,
    specStatusWriter = specStatusWriter,
    database = database,
  )
  return RunnerHarness(launcher, io, runner, captured, runRequest, specScratchStore)
}

@Suppress("LongParameterList")
private fun harnessRunner(
  launcher: RuntimeRecordingLauncher,
  recorder: FeatureTaskRuntimePhaseRecorder,
  goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  validator: FeatureTaskRuntimePhaseOutputValidator,
  runtimeConfig: RuntimeHarnessConfig,
  database: RuntimeFakeDatabaseSessionFactory,
  crashSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  diagnostics: RuntimeDiagnostics,
  specScratchStore: RecordingSpecScratchStore,
  specStatusWriter: RecordingSpecStatusWriter,
  decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
): FeatureTaskRuntimeRunner {
  val branchSetupRunner = FeatureTaskRuntimeBranchSetupRunner(recorder, runtimeConfig.branchSetup.gitOperations)
  val decompositionPlanner =
    if (runtimeConfig.useRealDecompositionPlanner) testDecompositionPlanner() else noOpDecompositionPlanner()
  val planningStopper = FeatureTaskRuntimePlanningStopper(
    validator,
    decompositionPlanner,
    decomposeTerminalRecorder,
    diagnostics,
  )
  return FeatureTaskRuntimeRunner(
    launcher,
    recorder,
    goalContinuationRecorder,
    runInvariantsStore,
    validator,
    runtimePhaseGates(
      branchSetupRunner,
      planningStopper,
      disabledRuntimeLifecycleTelemetry(database),
      runtimeConfig.branchSetup.gitOperations,
      testSpecGate(specScratchStore, specStatusWriter),
      runtimeConfig.planningProjectionValidator,
      runtimeConfig.buildReceiptValidator,
      runtimeConfig.sharedEvidenceResolver,
      runtimeConfig.diffResolver,
      recorder,
      runtimeConfig.validationGateRunner,
      runtimeConfig.validationGatePlatformManifests,
      harnessReviewDriverSyncingPendingVerifyFindings(runtimeConfig.reviewDriver),
    ),
    FeatureTaskRuntimeCrashReconciler(database, crashSupervisor),
    diagnostics,
  )
}

internal class TelemetryRunnerHarness(
  val runner: FeatureTaskRuntimeRunner,
  val lifecycle: RecordingLifecycleTelemetryRepository,
  val request: FeatureTaskRuntimeRunRequest,
  val database: RuntimeFakeDatabaseSessionFactory,
  val recorder: FeatureTaskRuntimePhaseRecorder,
) {
  fun seedPhase(phaseId: String, status: String, attemptCount: Int, agentId: String, outputArtifact: String?) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseStateForTest(phaseId, status, attemptCount, agentId, outputArtifact)
  }
}

internal fun telemetryRunnerHarness(
  launcher: RuntimeRecordingLauncher = RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) },
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
): TelemetryRunnerHarness {
  seedHarnessSpecIntentProjection(runtimeConfig.repoRoot, runtimeConfig.branchSetup.specReference)
  val repository = InMemoryRuntimeWorkflowRepository()
  val lifecycle = RecordingLifecycleTelemetryRepository()
  val database = RuntimeFakeDatabaseSessionFactory(repository, lifecycle)
  val recorder = FeatureTaskRuntimePhaseRecorder(
    database,
    NoopWorkflowSnapshotValidator,
    AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
    AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
  )
  val goalContinuationRecorder = FeatureTaskRuntimeGoalContinuationRecorder(database, NoopWorkflowSnapshotValidator)
  val decomposeTerminalRecorder = FeatureTaskRuntimeDecomposeTerminalRecorder(database, NoopWorkflowSnapshotValidator)
  val runInvariantsStore = FeatureTaskRuntimeRunInvariantsStore(database, NoopWorkflowSnapshotValidator)
  val branchSetupRunner = FeatureTaskRuntimeBranchSetupRunner(recorder, runtimeConfig.branchSetup.gitOperations)
  val decompositionPlanner =
    if (runtimeConfig.useRealDecompositionPlanner) testDecompositionPlanner() else noOpDecompositionPlanner()
  val planningStopper = FeatureTaskRuntimePlanningStopper(validator, decompositionPlanner, decomposeTerminalRecorder)
  val runner = FeatureTaskRuntimeRunner(
    launcher,
    recorder,
    goalContinuationRecorder,
    runInvariantsStore,
    validator,
    runtimePhaseGates(
      branchSetupRunner,
      planningStopper,
      FeatureTaskRuntimeLifecycleTelemetry(
        LifecycleTelemetryService(database, EnabledRuntimeTelemetrySettingsProvider),
      ),
      runtimeConfig.branchSetup.gitOperations,
      sharedEvidenceResolver = runtimeConfig.sharedEvidenceResolver,
      diffResolver = runtimeConfig.diffResolver,
      recorder = recorder,
      validationGateRunnerOverride = runtimeConfig.validationGateRunner,
      validationGatePlatformManifests = runtimeConfig.validationGatePlatformManifests,
      reviewDriver = harnessReviewDriverSyncingPendingVerifyFindings(runtimeConfig.reviewDriver),
    ),
    // Telemetry harness validates event emission, not crash reconciliation; no-op supervisor.
    FeatureTaskRuntimeCrashReconciler(database, NoopFeatureTaskRuntimeWorkerSupervisor),
  )
  val request = FeatureTaskRuntimeRunRequest(
    issueKey = ISSUE_KEY,
    workflowId = WORKFLOW_ID,
    sessionId = SESSION_ID,
    runInvariants = FeatureTaskRuntimeRunInvariants(
      specReference = runtimeConfig.branchSetup.specReference,
      featureSize = runtimeConfig.branchSetup.featureSize,
      acceptanceCriteria = listOf("AC-1", "AC-2"),
      mandatesAndOverrides = listOf("mandate-X"),
    ),
    invokedAgentId = INVOKED_AGENT,
    dbPathOverride = runtimeConfig.dbPathOverride,
    repoRoot = runtimeConfig.repoRoot,
  )
  return TelemetryRunnerHarness(runner, lifecycle, request, database, recorder)
}

private fun noOpDecompositionPlanner(): FeatureTaskRuntimeDecompositionPlanner = FeatureTaskRuntimeDecompositionPlanner(
  preparationRuntime = FeatureSpecPreparationRuntime { intake ->
    FeatureSpecPreparationDecision(
      issueKey = intake.issueKey,
      intendedOutcome = intake.intendedOutcome,
      acceptanceCriteria = intake.acceptanceCriteria,
      constraints = intake.constraints,
      nonGoals = intake.nonGoals,
      mode = FeatureSpecPreparationMode.SINGLE_SPEC,
    )
  },
  preparationWriter = FeatureSpecPreparationWriter(
    decompositionManifestValidator = testDecompositionManifestValidator,
    fileStore = TestDecompositionManifestFileStore,
  ),
)

private fun testDecompositionPlanner(): FeatureTaskRuntimeDecompositionPlanner = FeatureTaskRuntimeDecompositionPlanner(
  preparationRuntime = FeatureSpecPreparationRuntime(),
  preparationWriter = FeatureSpecPreparationWriter(
    decompositionManifestValidator = testDecompositionManifestValidator,
    fileStore = TestDecompositionManifestFileStore,
  ),
)

internal fun facts(stdout: String): AgentRunLaunchOutcome = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = 0,
  stdout = stdout,
  stderr = "",
  timedOut = false,
  spawnFailed = false,
)

private val PHASE_LINE = Regex("^Phase: ([a-z_-]+) ", setOf(RegexOption.MULTILINE))

internal fun phaseIdFromPrompt(prompt: String): String =
  PHASE_LINE.find(prompt)?.groupValues?.get(1) ?: error("Prompt did not contain a phase header: $prompt")

// The default harness launcher returns a schema-valid, phase-attributed output per phase so a forward
// run completes. Phase-aware so the implement phase carries the reconciliation report the runtime's
// mutating-phase gate requires (SKILL-85 Subtask 3); every other phase carries its generic output.
internal fun defaultPhaseAwareLauncher(): RuntimeRecordingLauncher = RuntimeRecordingLauncher { request ->
  facts(defaultPhaseOutput(request))
}

// A schema-valid output for the phase the prompt names. Mutating phases (implement) carry the
// reconciliation report the runtime gate now requires; every other phase keeps the minimal
// VALID_OUTPUT so existing recorded-artifact equality assertions are unchanged.
internal fun defaultPhaseOutput(request: GoalRunnerSubtaskLaunchRequest): String {
  val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
  return when {
    FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId) -> validJsonOutput(phaseId)
    // preplan and plan feed the bounded planning projections, so they must carry the declared
    // projection payload rather than the minimal VALID_OUTPUT envelope.
    phaseId == "preplan" || phaseId == "plan" -> validJsonOutput(phaseId)
    // A clean review must emit a verification signal (an empty findings array affirms no blocking
    // findings) or the review gate blocks (SKILL-85 Subtask 4 F-003).
    phaseId == "review" -> VALID_REVIEW_OUTPUT
    // A clean audit must likewise emit a verification signal (an empty gaps array affirms
    // every acceptance criterion is met) or the audit gate blocks (SKILL-85 Subtask 5 AC1).
    phaseId == "audit" -> VALID_AUDIT_OUTPUT
    phaseId == "verify_findings" -> verifyFindingsOutput()
    else -> validJsonOutput(phaseId)
  }
}

// Subtask 2: a synthetic non-mutating cycle over [preplan, plan] with a backward edge
// plan --needs_fix--> preplan, bounded by PLAN_FIX_CAP re-entries. Both phases are non-file-mutating
// so the cycle exercises the executor without entering branch setup.
private const val PLAN_FIX_CAP = 2

private val PLAN_FIX_CYCLE = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
  forwardPhaseIds = listOf("preplan", "plan"),
  backwardEdges = listOf(
    skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "plan",
      triggeringVerdict = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict("needs_fix"),
      destinationPhaseId = "preplan",
      loopId = "plan-fix",
      perEdgeCap = PLAN_FIX_CAP,
    ),
  ),
)

// SKILL-85 Subtask 3: a synthetic cycle whose backward edge re-enters the MUTATING implement phase.
// review --needs_fix--> implement, bounded by IMPLEMENT_FIX_CAP. The reopened span [implement, review]
// contains a mutating phase, so the remediation checkpoint boundary fires before re-entry.
private const val IMPLEMENT_FIX_CAP = 2

private val IMPLEMENT_FIX_CYCLE = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration(
  forwardPhaseIds = listOf("preplan", "plan", "implement", "audit", "review"),
  backwardEdges = listOf(
    skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "review",
      triggeringVerdict = skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      destinationPhaseId = "implement",
      loopId = "implement-fix",
      perEdgeCap = IMPLEMENT_FIX_CAP,
    ),
  ),
)

// A schema-valid review output carrying a top-level `verdict` wire string the transition function reads.
internal fun verdictReviewOutput(verdict: String): String = """
  {
    "contract_version": "0.3",
    "phase_id": "review",
    "status": "completed",
    "summary": "Review produced a validated output.",
    "verdict": "$verdict",
    "produced_outputs": {}
  }
""".trimIndent()

// SKILL-85 Subtask 4: the unique Blocker-finding message a changes_requested review carries, so a fix
// briefing and a cap-exhaustion block can be asserted to contain it.
internal const val REVIEW_BLOCKER_MESSAGE = "Foo.kt leaks a connection in the error path"

// A schema-valid review output whose findings drive the verdict: a Blocker finding => changes_requested
// (the runtime classifies from findings, no top-level verdict needed), an empty findings list => the
// run advances. The findings ride inside produced_outputs the way the runner reads them.
internal fun reviewFindingsOutput(
  changesRequested: Boolean,
  dispositionedBlockerIds: List<String> = emptyList(),
): String {
  val findings = if (changesRequested) {
    """{"severity": "blocker", "finding_id": "$REVIEW_FIX_BLOCKER_FINDING_ID", "message": "$REVIEW_BLOCKER_MESSAGE"}"""
  } else {
    ""
  }
  // A remediation pass must disposition every Blocker the prior pass emitted, with evidence; the
  // parse seam rejects an output that leaves one undisposed.
  val dispositions = dispositionedBlockerIds.joinToString(", ") { findingId ->
    """{"finding_id": "$findingId", "verdict": "${if (changesRequested) "unresolved" else "resolved"}", """ +
      """"evidence": ["Foo.kt:42 in the remediation delta"]}"""
  }
  return """
    {
      "contract_version": "0.3",
      "phase_id": "review",
      "status": "completed",
      "summary": "Review produced a validated output.",
      "produced_outputs": {"findings": [$findings], "blocker_dispositions": [$dispositions]}
    }
  """.trimIndent()
}

// The real M1 review_fix launcher: review returns changes_requested findings until [convergeOnReview]
// (1-based review launch index at which it first approves); a value above the cap never converges.
// implement_fix and every other phase return their schema-valid reconciled output.
internal fun reviewFixDriver(convergeOnReview: Int): skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    val findings = if (reviewPasses < convergeOnReview) {
      listOf(
        skillbill.review.model.ParallelReviewMergedFinding(
          fNumber = REVIEW_FIX_BLOCKER_FINDING_ID,
          agentIds = listOf(request.agent1Id),
          severity = skillbill.review.model.ParallelReviewSeverity.BLOCKER,
          confidence = "High",
          location = "Foo.kt:1",
          description = REVIEW_BLOCKER_MESSAGE,
        ),
      )
    } else {
      emptyList()
    }
    harnessPendingVerifyFindingIds = findings.map { it.fNumber }
    skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
      mergeResult = skillbill.review.model.ParallelReviewMergeResult(
        findings = findings,
        formattedOutput = if (findings.isEmpty()) "NO_FINDINGS" else "findings",
      ),
    )
  }
}

internal fun reviewFixRuntimeConfig(
  convergeOnReview: Int,
  gitOperations: RecordingWorkflowGitOperations = RecordingWorkflowGitOperations(),
): RuntimeHarnessConfig = RuntimeHarnessConfig(
  branchSetup = BranchSetupTestConfig(gitOperations = gitOperations),
  reviewDriver = reviewFixDriver(convergeOnReview),
)

internal fun crashingRemediationReviewDriver(): skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    when (reviewPasses) {
      2 ->
        skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
          lane1 = skillbill.application.model.ParallelReviewLaneStatus(
            agentId = request.agent1Id,
            success = false,
            failureReason = "spawn failed",
          ),
        )
      else -> {
        val findings = if (reviewPasses == 1) {
          listOf(
            skillbill.review.model.ParallelReviewMergedFinding(
              fNumber = "F-001",
              agentIds = listOf(request.agent1Id),
              severity = skillbill.review.model.ParallelReviewSeverity.BLOCKER,
              confidence = "High",
              location = "Foo.kt:1",
              description = REVIEW_BLOCKER_MESSAGE,
            ),
          )
        } else {
          emptyList()
        }
        skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
          mergeResult = skillbill.review.model.ParallelReviewMergeResult(
            findings = findings,
            formattedOutput = if (findings.isEmpty()) "NO_FINDINGS" else "findings",
          ),
        )
      }
    }
  }
}

internal fun throwingBudgetReviewDriver(): skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver =
  skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver {
    throw skillbill.review.context.model.ReviewContextBudgetExceededException(
      skillbill.review.context.model.ReviewContextBudgetExceeded(
        lane = "architecture",
        budgetKind = "parent_packet_bytes",
        configuredLimit = 524_288,
        observedValue = 584_846,
        packetDigest = "a".repeat(64),
        assignmentDigest = "b".repeat(64),
        enforceable = true,
      ),
    )
  }

internal fun failingReviewDriver(
  failOnPass: Int,
  failureReason: String,
): skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    if (reviewPasses == failOnPass) {
      skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
        lane1 = skillbill.application.model.ParallelReviewLaneStatus(
          agentId = request.agent1Id,
          success = false,
          failureReason = failureReason,
        ),
      )
    } else {
      skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY.run(request)
    }
  }
}

internal fun crashingReviewFixDriver(
  convergeOnReview: Int,
  crashOnPass: Int,
  shouldCrash: () -> Boolean,
): skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    if (shouldCrash() && reviewPasses == crashOnPass) {
      skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
        lane1 = skillbill.application.model.ParallelReviewLaneStatus(
          agentId = request.agent1Id,
          success = false,
          failureReason = "spawn failed",
        ),
      )
    } else {
      val findings = if (reviewPasses < convergeOnReview) {
        listOf(
          skillbill.review.model.ParallelReviewMergedFinding(
            fNumber = "F-001",
            agentIds = listOf(request.agent1Id),
            severity = skillbill.review.model.ParallelReviewSeverity.BLOCKER,
            confidence = "High",
            location = "Foo.kt:1",
            description = REVIEW_BLOCKER_MESSAGE,
          ),
        )
      } else {
        emptyList()
      }
      skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
        mergeResult = skillbill.review.model.ParallelReviewMergeResult(
          findings = findings,
          formattedOutput = if (findings.isEmpty()) "NO_FINDINGS" else "findings",
        ),
      )
    }
  }
}

internal fun reviewFixLauncher(
  convergeOnReview: Int,
  onReviewLaunch: (Int) -> Unit = {},
  onPhaseLaunch: (String) -> Unit = {},
): RuntimeRecordingLauncher {
  var reviewLaunches = 0
  return RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    onPhaseLaunch(phaseId)
    if (phaseId == "review") {
      reviewLaunches += 1
      onReviewLaunch(reviewLaunches)
      val changesRequested = reviewLaunches < convergeOnReview
      harnessPendingVerifyFindingIds = if (changesRequested) listOf(REVIEW_FIX_BLOCKER_FINDING_ID) else emptyList()
      facts(
        reviewFindingsOutput(
          changesRequested = changesRequested,
          dispositionedBlockerIds = if (reviewLaunches > 1) listOf("pass1-blocker-1") else emptyList(),
        ),
      )
    } else {
      facts(validJsonOutput(phaseId))
    }
  }
}

// A commit_push phase output that completes without the outcome message the runtime commits from.
// SKILL-190 moved commit authorship into the runtime, so this is the payload the finalisation gate
// must refuse rather than publish under a provisional subject.
private val COMMIT_PUSH_NO_SHA_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "commit_push",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": {"commit_push_result": {"status": "committed"}}
  }
""".trimIndent()

private val COMMIT_PUSH_BLOCKED_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "commit_push",
    "status": "blocked",
    "summary": "Validation failed before commit.",
    "produced_outputs": {
      "commit_push_result": {
        "commit_sha": null,
        "pushed_status": "not_attempted"
      },
      "blocking_reasons": ["Working tree contains unrelated changes."]
    }
  }
""".trimIndent()

private val VALIDATE_BLOCKED_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "validate",
    "status": "blocked",
    "summary": "Validation failed before finalization.",
    "produced_outputs": {
      "validation_result": "fail",
      "blocking_reasons": ["Repository validation still fails."]
    }
  }
""".trimIndent()

// Launcher for a suppress_pr goal-continuation run: every phase returns a valid output, with the
// commit_push phase returning the supplied payload so a test can vary whether a SHA is present.
private fun goalContinuationLauncher(commitPushOutput: String): RuntimeRecordingLauncher =
  RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    facts(if (phaseId == "commit_push") commitPushOutput else validJsonOutput(phaseId))
  }

// Builds a suppress_pr goal-continuation harness with a caller-supplied git fake and launcher so a
// test can seed the measurable/blank HEAD and the commit_push payload independently.
private fun goalContinuationHarness(
  repoRoot: Path,
  git: RecordingWorkflowGitOperations,
  launcher: RuntimeRecordingLauncher,
  reviewDriver: skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver =
    skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver.EMPTY,
): RunnerHarness = runnerHarness(
  launcher = launcher,
  agentAssignment = phasePerAgentAssignment(),
  runtimeConfig = RuntimeHarnessConfig(
    branchSetup = BranchSetupTestConfig(gitOperations = git),
    repoRoot = repoRoot,
    goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
      parentIssueKey = ISSUE_KEY,
      subtaskId = 5,
      goalBranch = "feat/existing-runtime-branch",
      suppressPr = true,
      parentWorkflowId = "wfl-parent",
      reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
    ),
    useRealDecompositionPlanner = true,
    reviewDriver = reviewDriver,
  ),
)

private val DECOMPOSE_PLAN_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "mode": "decompose",
      "reason": "Plan needs ordered subtasks.",
      "feature_name": "runtime decomposition parity",
      "parent_spec_overview": "Split the runtime work into ordered subtasks.",
      "validation_strategy": "bill-code-check",
      "base_branch": "main",
      "feature_branch": "feat/SKILL-65-runtime-decomposition-parity",
      "subtasks": [
        {
          "id": 1,
          "name": "domain contracts",
          "scope": "Add typed plan outcome detection.",
          "acceptance_criteria": ["Detect decompose mode."],
          "non_goals": [],
          "dependency_notes": "First subtask.",
          "validation_strategy": "unit tests",
          "next_path": "Work subtask 2 next.",
          "depends_on": []
        },
        {
          "id": 2,
          "name": "runtime stop",
          "scope": "Stop after writing decomposition.",
          "acceptance_criteria": ["Do not advance to implement."],
          "non_goals": [],
          "dependency_notes": "Depends on subtask 1.",
          "validation_strategy": "unit tests",
          "next_path": "Return to the parent workflow.",
          "depends_on": [1]
        }
      ]
    }
  }
""".trimIndent()

// A plan envelope that declares mode=decompose but emits a malformed package: the second subtask
// is missing its required `name`, so the typed projection loud-fails at parse. The runtime must
// turn this into a Blocked outcome rather than letting the exception escape run().
private val MALFORMED_DECOMPOSE_PLAN_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "mode": "decompose",
      "reason": "Plan needs ordered subtasks.",
      "feature_name": "runtime decomposition parity",
      "parent_spec_overview": "Split the runtime work into ordered subtasks.",
      "validation_strategy": "bill-code-check",
      "base_branch": "main",
      "feature_branch": "feat/SKILL-65-runtime-decomposition-parity",
      "subtasks": [
        {
          "id": 1,
          "name": "domain contracts",
          "scope": "Add typed plan outcome detection.",
          "acceptance_criteria": ["Detect decompose mode."],
          "non_goals": [],
          "dependency_notes": "First subtask.",
          "validation_strategy": "unit tests",
          "next_path": "Work subtask 2 next.",
          "depends_on": []
        },
        {
          "id": 2,
          "scope": "Stop after writing decomposition.",
          "acceptance_criteria": ["Do not advance to implement."],
          "non_goals": [],
          "dependency_notes": "Depends on subtask 1.",
          "validation_strategy": "unit tests",
          "next_path": "Return to the parent workflow.",
          "depends_on": [1]
        }
      ]
    }
  }
""".trimIndent()

// A plan envelope that declares mode=decompose with a DECODER-VALID package (every subtask carries
// all required fields with correct types) but is WRITER-INVALID: the subtask ids descend [2, 1], so
// the typed decoder accepts it while FeatureSpecPreparationWriter.validateDecomposedSubtasks rejects
// the non-ascending order with InvalidFeatureSpecPreparationRequestError. The runtime must turn this
// writer business-rule rejection into a Blocked outcome rather than letting it escape run().
private val WRITER_INVALID_DECOMPOSE_PLAN_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "mode": "decompose",
      "reason": "Plan needs ordered subtasks.",
      "feature_name": "runtime decomposition parity",
      "parent_spec_overview": "Split the runtime work into ordered subtasks.",
      "validation_strategy": "bill-code-check",
      "base_branch": "main",
      "feature_branch": "feat/SKILL-65-runtime-decomposition-parity",
      "subtasks": [
        {
          "id": 2,
          "name": "runtime stop",
          "scope": "Stop after writing decomposition.",
          "acceptance_criteria": ["Do not advance to implement."],
          "non_goals": [],
          "dependency_notes": "Listed first but ids descend.",
          "validation_strategy": "unit tests",
          "next_path": "Return to the parent workflow.",
          "depends_on": []
        },
        {
          "id": 1,
          "name": "domain contracts",
          "scope": "Add typed plan outcome detection.",
          "acceptance_criteria": ["Detect decompose mode."],
          "non_goals": [],
          "dependency_notes": "Listed second; out of ascending order.",
          "validation_strategy": "unit tests",
          "next_path": "Work subtask 2 next.",
          "depends_on": []
        }
      ]
    }
  }
""".trimIndent()

// An infrastructure spawn failure (no exit status, empty stdout).
internal fun spawnFailedFacts(): AgentRunLaunchOutcome = AgentRunLaunchFacts(
  agent = InstallAgent.CLAUDE,
  exitStatus = null,
  stdout = "",
  stderr = "spawn failed",
  timedOut = false,
  spawnFailed = true,
)

internal class RuntimeRecordingLauncher(
  private val handler: (GoalRunnerSubtaskLaunchRequest) -> AgentRunLaunchOutcome,
) : GoalRunnerSubtaskLauncher {
  val requests = mutableListOf<GoalRunnerSubtaskLaunchRequest>()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    requests += request
    return handler(request)
  }
}

// A schema validator that rejects only the named phases.
private class ThrowingValidator(private val failPhases: Set<String>) : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    if (sourceLabel in failPhases) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "rejected by fake validator")
    }
  }
}

internal object AlwaysValidValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) = Unit
}

private object RepairingImplementOutputValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) = Unit

  override fun validatePhaseOutput(
    phaseOutputText: String,
    sourceLabel: String,
  ): FeatureTaskRuntimePhaseOutputValidationResult {
    if (sourceLabel != "implement") return AlwaysValidValidator.validatePhaseOutput(phaseOutputText, sourceLabel)
    val canonical = validJsonOutput(sourceLabel)
    return FeatureTaskRuntimePhaseOutputValidationResult.AcceptedAfterRepair(
      normalizedOutput = NormalizedFeatureTaskRuntimePhaseOutput(
        canonicalJson = canonical,
        envelope = normalizePhaseOutput(canonical, sourceLabel).envelope,
      ),
      evidence = FeatureTaskRuntimePhaseOutputRepairEvidence(
        format = FeatureTaskRuntimePhaseOutputFormat.JSON,
        originalDigest = "a".repeat(64),
        repairedDigest = "b".repeat(64),
        operation = FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER,
        sourceLocation = FeatureTaskRuntimePhaseOutputSourceLocation(sourceLabel, 0, 1, 1),
      ),
    )
  }
}

internal object CanonicalWrapperTestValidator : FeatureTaskRuntimePhaseOutputValidator {
  private val fencedBlock = Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    validateAndReadPhaseOutput(phaseOutputText, sourceLabel)
  }

  override fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> {
    val trimmed = phaseOutputText.trim()
    val candidate = fencedBlock.findAll(trimmed).lastOrNull()?.groupValues?.get(1)?.trim()
      ?: trimmed.substring(trimmed.indexOf('{'), trimmed.lastIndexOf('}') + 1)
    val envelope = skillbill.contracts.JsonSupport.parseObjectOrNull(candidate)
      ?.let(skillbill.contracts.JsonSupport::jsonElementToValue)
      ?.let(skillbill.contracts.JsonSupport::anyToStringAnyMap)
      ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "test output is not an object")
    if (envelope["phase_id"] != sourceLabel) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "phase_id does not match")
    }
    return envelope
  }
}

// Records every checkout, with configurable currentBranch/checkoutBranch results. The default
// currentBranch reports an already-feature branch so existing tests never enter the create path;
// branch-setup tests override these to drive starts-on-default / cannot-establish / resume cases.
internal class RecordingWorkflowGitOperations(
  var currentBranchValue: String = "feat/existing-runtime-branch",
  var currentBranchResult: WorkflowGitOperationResult? = null,
  var checkoutResult: WorkflowGitOperationResult? = null,
  // When set, a successful checkout updates the working-tree branch the next currentBranch read
  // reports, modelling git HEAD actually moving so the runner's post-checkout re-confirmation sees
  // the landed branch. Defaults to the checkout target.
  var landedBranchAfterCheckout: String? = null,
  // Branch names the repository reports as existing. null models every queried branch as existing
  // (the common case for re-attach tests); a concrete set models a repository where the persisted
  // branch may have been deleted. branchExistsResult overrides with a raw result for error cases.
  var existingBranches: Set<String>? = null,
  var branchExistsResult: WorkflowGitOperationResult? = null,
) : WorkflowGitOperations,
  CheckpointHistoryGitOperationsProvider,
  GoalSubtaskReviewGitOperationsProvider,
  RepositoryFingerprintGitOperationsProvider,
  RepositoryOwnedPathsGitOperationsProvider,
  RuntimePhaseFileManifestGitOperationsProvider,
  ScopedStagingGitOperationsProvider {
  // Seeded git HEAD for the SKILL-68 capture-at-source fallback: blank models an unmeasurable HEAD;
  // a concrete value models a measurable commit. headCommitShaResult overrides with a raw result.
  var headCommitShaValue: String = ""
  var headCommitShaResult: WorkflowGitOperationResult? = null
  val runtimePhaseHeadCommitSequence = ArrayDeque<String>()
  var changedPathsBetweenCommitsValue: String = ""

  // Models the working-tree cleanliness the remediation-checkpoint boundary reads: blank => clean
  // (no checkpoint commit), non-blank => dirty (a checkpoint commit is created). worktreeStatusResult
  // overrides with a raw result to model an unreadable worktree.
  var worktreeStatusValue: String = ""
  var worktreeStatusResult: WorkflowGitOperationResult? = null
  val worktreeStatusSequence = ArrayDeque<String>()

  // NUL-delimited owned-path inventory, the same `-z` plumbing representation the goal-child baseline
  // is written in. Seeded as a path list for readability.
  var ownedPathsValue: List<String> = emptyList()

  // Overrides the inventory with a raw result, to model an unmeasurable owned-path read.
  var ownedPathsResult: WorkflowGitOperationResult? = null
  val repositoryFingerprintSequence = ArrayDeque<String>()
  var repositoryFingerprintValue: String? = null
  var repositoryFingerprintCalls: Int = 0

  // Records every remediation-checkpoint commit message; createCommitResult overrides the result to
  // model a failed checkpoint commit.
  val createCommitMessages = mutableListOf<String>()
  var createCommitResult: WorkflowGitOperationResult? = null

  // SKILL-190 subtask-commit ceremony. The branch reports unpushed commits by default: a fake repo has
  // no remote, and a pushed HEAD would refuse every amend and collapse the ceremony back to create.
  var localBranchHasUnpushedCommitsValue: Boolean = true
  var headCommitMessageValue: String = ""

  // Every message the checkpoint amended HEAD with, in call order.
  val amendCommitMessages = mutableListOf<String>()
  var amendHeadCommitResult: WorkflowGitOperationResult? = null

  // Checkpoint refs written by name, and the ordered write log. updateCheckpointRefResult models a
  // ref write that fails so the amend must not run.
  val checkpointRefs = mutableMapOf<String, String>()
  val updateCheckpointRefCalls = mutableListOf<Pair<String, String>>()
  var updateCheckpointRefResult: WorkflowGitOperationResult? = null

  // A ref lookup that fails rather than reporting an absent ref, so occupancy is undetermined.
  var resolveCheckpointRefResult: WorkflowGitOperationResult? = null
  var onResolveCheckpointRef: ((String) -> WorkflowGitOperationResult?)? = null
  var onResolveCommit: ((String) -> WorkflowGitOperationResult?)? = null

  // When true, a remediation-checkpoint createCommit returns a malformed sha so the paired base
  // record fails GoalSubtaskReviewState validation and the soft-reset rollback path is exercised.
  var invalidShaOnRemediationCommit: Boolean = false
  val resetSoftToCommitCalls = mutableListOf<String>()
  var resetSoftToCommitResult: WorkflowGitOperationResult? = null

  // Ancestor→descendant pairs that report false; all other pairs report true when both SHAs are set.
  val nonAncestorPairs = mutableSetOf<Pair<String, String>>()

  // Records every path the checkpoint staged, in call order; stagePathsResult overrides the result
  // to model a failed staging.
  val stagePathsCalls = mutableListOf<String>()
  var stagePathsResult: WorkflowGitOperationResult? = null

  // Pre-checkpoint index snapshot and the restores performed against it; restoreIndexStateResult
  // overrides the result to model a restore that itself fails.
  var indexSnapshotValue: String = ""
  var captureIndexStateResult: WorkflowGitOperationResult? = null
  val restoreIndexStateCalls = mutableListOf<String>()
  var restoreIndexStateResult: WorkflowGitOperationResult? = null

  // Working-tree content identity per path. A test mutates it between phases to model a foreign
  // edit landing on a path this workflow owns; anything unset reads as unchanged.
  val contentIdentities = mutableMapOf<String, String>()

  // Fires when the checkpoint reads the index, which is the moment between a phase ending and its
  // checkpoint staging: the window a concurrent foreign edit lands in.
  var onStagedPathsRead: (() -> Unit)? = null

  // Paths already staged before the checkpoint runs, modelling a foreign index entry.
  var stagedPathsValue: List<String> = emptyList()
  var stagedPathsResult: WorkflowGitOperationResult? = null
  val goalReviewBuildInputs = mutableListOf<GoalSubtaskReviewBaseline>()
  val goalReviewBuildResults = ArrayDeque<GoalSubtaskReviewInputResult>()
  var goalReviewTrackedDelta: String = ""
  var goalReviewRecoveredBaseline: GoalSubtaskReviewBaseline? = null
  var goalReviewRecoverCalls: Int = 0
  val goalReviewRecoverRequests =
    mutableListOf<skillbill.ports.workflow.model.GoalSubtaskReviewBaselineRecoveryRequest>()

  data class CheckoutCall(val branch: String, val baseBranch: String?)

  val checkoutCalls = mutableListOf<CheckoutCall>()
  val branchExistsCalls = mutableListOf<String>()
  var currentBranchCalls: Int = 0

  override fun checkoutBranch(repoRoot: Path, branch: String, baseBranch: String?): WorkflowGitOperationResult {
    checkoutCalls += CheckoutCall(branch, baseBranch)
    val result = checkoutResult ?: WorkflowGitOperationResult(status = "ok", value = branch)
    if (result.ok) {
      currentBranchValue = landedBranchAfterCheckout ?: branch
    }
    return result
  }

  override fun branchExists(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    branchExistsCalls += branch
    branchExistsResult?.let { return it }
    val exists = existingBranches?.contains(branch.trim()) ?: true
    return WorkflowGitOperationResult(status = "ok", value = exists.toString())
  }

  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult {
    currentBranchCalls++
    return currentBranchResult ?: WorkflowGitOperationResult(status = "ok", value = currentBranchValue)
  }

  // A distinct, well-formed sha per commit: the durable checkpoint identity is keyed on commit sha,
  // so a fake that returned one constant would collapse every checkpoint into a single record.
  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    createCommitMessages += message
    if (invalidShaOnRemediationCommit && message.contains("remediation checkpoint")) {
      // Simulate git advancing HEAD to a commit object whose sha the durable models reject.
      val bogus = "not-a-valid-commit-sha"
      headCommitShaValue = bogus
      return WorkflowGitOperationResult(status = "ok", value = bogus)
    }
    val result = createCommitResult
      ?: WorkflowGitOperationResult(status = "ok", value = createCommitMessages.size.toString(16).padStart(40, '0'))
    if (result.ok && result.value.isNotBlank()) {
      headCommitShaValue = result.value.trim()
      headCommitMessageValue = message
    }
    return result
  }

  override fun localBranchHasUnpushedCommits(repoRoot: Path, branch: String): WorkflowGitOperationResult =
    WorkflowGitOperationResult(status = "ok", value = localBranchHasUnpushedCommitsValue.toString())

  override val checkpointHistoryOperations: CheckpointHistoryGitOperations =
    object : CheckpointHistoryGitOperations {
      override fun amendHeadCommit(
        repoRoot: Path,
        expectedOwnedHeadSha: String,
        replacementMessage: String?,
        allowUnchangedIndex: Boolean,
      ): WorkflowGitOperationResult {
        amendHeadCommitResult?.let { return it }
        if (expectedOwnedHeadSha.trim() != headCommitShaValue.trim()) {
          return WorkflowGitOperationResult(
            status = "error",
            error = "HEAD is '$headCommitShaValue' but the caller owns '$expectedOwnedHeadSha'.",
          )
        }
        replacementMessage?.let { message ->
          amendCommitMessages += message
          if (invalidShaOnRemediationCommit && message.contains("remediation checkpoint")) {
            createCommitMessages += message
            val bogus = "not-a-valid-commit-sha"
            headCommitShaValue = bogus
            return WorkflowGitOperationResult(status = "ok", value = bogus)
          }
        }
        headCommitShaValue = "a${amendCommitMessages.size.toString(16)}".padStart(40, '0')
        headCommitMessageValue = replacementMessage ?: headCommitMessageValue
        return WorkflowGitOperationResult(status = "ok", value = headCommitShaValue)
      }

      override fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult =
        WorkflowGitOperationResult(status = "ok", value = headCommitMessageValue)

      override fun updateRef(
        repoRoot: Path,
        namespacePrefix: String,
        refName: String,
        targetSha: String,
      ): WorkflowGitOperationResult {
        updateCheckpointRefCalls += refName to targetSha
        updateCheckpointRefResult?.let { return it }
        checkpointRefs[refName] = targetSha
        return WorkflowGitOperationResult(status = "ok", value = refName)
      }

      override fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult =
        onResolveCheckpointRef?.invoke(refName)
          ?: resolveCheckpointRefResult
          ?: WorkflowGitOperationResult(status = "ok", value = checkpointRefs[refName].orEmpty())

      override fun listRefs(repoRoot: Path, namespacePrefix: String): WorkflowGitOperationResult =
        WorkflowGitOperationResult(
          status = "ok",
          value = checkpointRefs.entries.joinToString("") { (ref, sha) -> "$sha\u0000$ref\u0000" },
        )

      override fun deleteRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult {
        checkpointRefs.remove(refName)
        return WorkflowGitOperationResult(status = "ok", value = refName)
      }
    }

  override fun resetSoftToCommit(repoRoot: Path, commitSha: String): WorkflowGitOperationResult {
    resetSoftToCommitCalls += commitSha.trim()
    val result = resetSoftToCommitResult ?: WorkflowGitOperationResult(status = "ok", value = commitSha.trim())
    if (result.ok) {
      headCommitShaValue = commitSha.trim()
    }
    return result
  }

  override fun isCommitAncestor(
    repoRoot: Path,
    ancestorSha: String,
    descendantSha: String,
  ): WorkflowGitOperationResult {
    val ancestor = ancestorSha.trim()
    val descendant = descendantSha.trim()
    if (ancestor.isBlank() || descendant.isBlank()) {
      return WorkflowGitOperationResult(status = "error", error = "Ancestor and descendant required.")
    }
    val reachable = ancestor == descendant || (ancestor to descendant) !in nonAncestorPairs
    return WorkflowGitOperationResult(status = "ok", value = if (reachable) "true" else "false")
  }

  var headCommitShaCalls: Int = 0

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult {
    headCommitShaCalls++
    return headCommitShaResult ?: WorkflowGitOperationResult(status = "ok", value = headCommitShaValue)
  }

  val pushedBranches: MutableList<String> = mutableListOf()
  val leasePushedBranches: MutableList<String> = mutableListOf()
  var pushBranchResult: WorkflowGitOperationResult? = null

  override fun pushBranch(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    pushedBranches += branch
    return pushBranchResult ?: WorkflowGitOperationResult(status = "ok", value = branch)
  }

  override fun pushBranchWithLease(repoRoot: Path, branch: String): WorkflowGitOperationResult {
    leasePushedBranches += branch
    return pushBranchResult ?: WorkflowGitOperationResult(status = "ok", value = branch)
  }

  override fun resolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult =
    onResolveCommit?.invoke(revision)
      // No remote-tracking ref exists in this fake, so an `origin/<branch>` lookup fails exactly as it
      // does in a repository whose branch was never pushed. Finalisation reads that as "not published".
      ?: if (revision.startsWith("origin/")) {
        WorkflowGitOperationResult(
          status = "error",
          error = "Revision '$revision' does not name a commit in this repository.",
        )
      } else {
        WorkflowGitOperationResult(
          status = "ok",
          value = revision.takeIf { it.matches(Regex("^[0-9a-fA-F]{40,64}$")) } ?: COMMITTED_HEAD_SHA,
        )
      }

  override val runtimePhaseFileManifestOperations: RuntimePhaseFileManifestGitOperations =
    object : RuntimePhaseFileManifestGitOperations {
      override fun headCommit(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult(
        status = "ok",
        value = runtimePhaseHeadCommitSequence.removeFirstOrNull().orEmpty(),
      )

      override fun changedPathsBetweenCommits(
        repoRoot: Path,
        beforeCommit: String,
        afterCommit: String,
      ): WorkflowGitOperationResult = WorkflowGitOperationResult(
        status = "ok",
        value = if (beforeCommit == afterCommit) "" else changedPathsBetweenCommitsValue,
      )
    }

  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = expectedBaseBranch)

  override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
    worktreeStatusResult ?: WorkflowGitOperationResult(
      status = "ok",
      value = worktreeStatusSequence.removeFirstOrNull() ?: worktreeStatusValue,
    )

  override val scopedStagingOperations: ScopedStagingGitOperations =
    object : ScopedStagingGitOperations {
      override fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult {
        stagePathsCalls += paths
        return stagePathsResult ?: WorkflowGitOperationResult(status = "ok", value = "")
      }

      override fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
        captureIndexStateResult ?: WorkflowGitOperationResult(status = "ok", value = indexSnapshotValue)

      override fun restoreIndexState(
        repoRoot: Path,
        paths: List<String>,
        snapshot: String,
      ): WorkflowGitOperationResult {
        restoreIndexStateCalls += snapshot
        return restoreIndexStateResult ?: WorkflowGitOperationResult(status = "ok", value = "")
      }

      override fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult {
        onStagedPathsRead?.invoke()
        return stagedPathsResult ?: WorkflowGitOperationResult(
          status = "ok",
          value = stagedPathsValue.joinToString(separator = "") { "$it\u0000" },
        )
      }

      override fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
        WorkflowGitOperationResult(
          status = "ok",
          value = paths.joinToString(separator = "\u0000") { path ->
            "${contentIdentities[path] ?: "identity"}\t$path"
          },
        )
    }

  override val repositoryOwnedPathsOperations: RepositoryOwnedPathsGitOperations =
    object : RepositoryOwnedPathsGitOperations {
      override fun ownedPaths(repoRoot: Path): WorkflowGitOperationResult = ownedPathsResult
        ?: WorkflowGitOperationResult(
          status = "ok",
          value = ownedPathsValue.joinToString(separator = "") { "$it\u0000" },
        )
    }

  override val repositoryFingerprintOperations: RepositoryFingerprintGitOperations =
    object : RepositoryFingerprintGitOperations {
      override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult {
        repositoryFingerprintCalls += 1
        return WorkflowGitOperationResult(
          status = "ok",
          value = repositoryFingerprintSequence.removeFirstOrNull()
            ?: repositoryFingerprintValue
            ?: "repository-fingerprint-$repositoryFingerprintCalls",
        )
      }

      override fun repositoryCheckpointFingerprint(
        repoRoot: Path,
        baseCommit: String?,
        headCommit: String,
        ownedPaths: List<String>,
      ): WorkflowGitOperationResult {
        repositoryFingerprintCalls += 1
        val scopeHash = listOf(
          baseCommit.orEmpty(),
          headCommit,
          ownedPaths.distinct().sorted().joinToString("\u0000"),
        ).joinToString("\u0000").hashCode().toUInt().toString(16)
        return WorkflowGitOperationResult(
          status = "ok",
          value = repositoryFingerprintSequence.removeFirstOrNull()
            ?: repositoryFingerprintValue
            ?: "repository-checkpoint-$scopeHash",
        )
      }
    }

  override fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult = WorkflowWorktreeActivityResult(
    status = "ok",
    changedFileSummary = GoalObservabilityChangedFileSummary(
      total = 0,
      added = 0,
      modified = 0,
      deleted = 0,
      renamed = 0,
      untracked = 0,
    ),
    diffStat = GoalObservabilityDiffStat(filesChanged = 0, insertions = 0, deletions = 0),
  )

  override fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult = WorkflowSelectedDiffHunksResult(
    status = "ok",
    selectedDiffHunks = GoalObservabilitySelectedDiffHunks(),
  )

  override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations =
    object : GoalSubtaskReviewGitOperations {
      override fun captureBaseline(repoRoot: Path, expectedBranch: String) = GoalSubtaskReviewBaselineResult(
        status = "ok",
        baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
      )

      override fun buildInput(
        repoRoot: Path,
        baseline: GoalSubtaskReviewBaseline,
        expectedBranch: String,
      ): GoalSubtaskReviewInputResult {
        goalReviewBuildInputs += baseline
        return goalReviewBuildResults.removeFirstOrNull() ?: GoalSubtaskReviewInputResult(
          status = "ok",
          input = GoalSubtaskReviewInput(
            reviewBaseSha = baseline.reviewBaseSha,
            currentHeadSha = baseline.reviewBaseSha,
            trackedDelta = goalReviewTrackedDelta,
            ownedUntrackedPatches = "",
          ),
        )
      }

      override fun recoverBaseline(
        repoRoot: Path,
        request: skillbill.ports.workflow.model.GoalSubtaskReviewBaselineRecoveryRequest,
        expectedBranch: String,
      ): GoalSubtaskReviewBaselineResult {
        goalReviewRecoverCalls++
        goalReviewRecoverRequests += request
        return goalReviewRecoveredBaseline?.let { GoalSubtaskReviewBaselineResult(status = "ok", baseline = it) }
          ?: GoalSubtaskReviewBaselineResult(status = "error", error = "no recovered baseline configured")
      }
    }
}

// The runner only drives openRecord/updateRecord (no snapshotView casts), so a
// no-op snapshot validator is sufficient here.
private object NoopWorkflowSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
}

private fun FeatureTaskRuntimePhaseRecorder.recordPhaseStateForTest(
  phaseId: String,
  status: String,
  attemptCount: Int,
  resolvedAgentId: String,
  outputArtifact: String?,
): Boolean = recordPhaseState(
  skillbill.application.model.FeatureTaskRuntimePhaseStateRequest(
    workflowId = WORKFLOW_ID,
    phaseId = phaseId,
    status = status,
    attemptCount = attemptCount,
    resolvedAgentId = resolvedAgentId,
    finished = status == "completed",
    outputArtifact = outputArtifact,
  ),
)

internal data class ProducerEvidenceKey(
  val workflowId: String,
  val phaseId: String,
  val generation: Int,
  val attempt: Int,
  val agentId: String,
  val repairTurn: Int = 0,
)

internal fun samePayload(left: ByteArray?, right: ByteArray?): Boolean =
  (left == null && right == null) || (left != null && right != null && left.contentEquals(right))

@Suppress("UNCHECKED_CAST")
private fun <T> noopPort(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
  defaultPortReturn(method)
} as T

private fun defaultPortReturn(method: Method): Any? = when {
  method.returnType == Void.TYPE -> null
  List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
  Map::class.java.isAssignableFrom(method.returnType) -> emptyMap<Any, Any>()
  method.returnType == java.lang.Boolean.TYPE -> false
  method.returnType == Integer.TYPE -> 0
  method.returnType == java.lang.Long.TYPE -> 0L
  method.returnType == java.lang.Double.TYPE -> 0.0
  else -> null
}

private fun recordHarnessFindingVerdicts(
  verdicts: MutableList<skillbill.review.model.ReviewFindingVerdict>,
  args: Array<out Any>?,
) {
  @Suppress("UNCHECKED_CAST")
  val incoming = args?.getOrNull(1) as? List<skillbill.review.model.ReviewFindingVerdict> ?: return
  incoming.forEach { verdict ->
    verdicts.removeAll { it.findingRef == verdict.findingRef && it.stage == verdict.stage }
    verdicts += verdict
  }
}

private fun harnessReviewRepository(): ReviewRepository {
  val verdicts = mutableListOf<skillbill.review.model.ReviewFindingVerdict>()
  @Suppress("UNCHECKED_CAST")
  return Proxy.newProxyInstance(
    ReviewRepository::class.java.classLoader,
    arrayOf(ReviewRepository::class.java),
  ) { _, method, args ->
    when (method.name) {
      "fetchFindingVerdicts" -> verdicts.toList()
      "recordFindingVerdicts" -> recordHarnessFindingVerdicts(verdicts, args)
      else -> defaultPortReturn(method)
    }
  } as ReviewRepository
}

internal class RuntimeFakeDatabaseSessionFactory(
  private val repository: InMemoryRuntimeWorkflowRepository,
  private val lifecycle: LifecycleTelemetryRepository = RecordingLifecycleTelemetryRepository(),
  private val knownIssue: Boolean = true,
  private val rejectedOutputDiagnosticsAvailable: Boolean = true,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/metrics.db")
  val transactionDbOverrides = mutableListOf<String?>()
  val ledgerRows = mutableListOf<skillbill.goalrunner.model.UnaddressedFinding>()
  val outcomeRows = mutableListOf<skillbill.goalrunner.model.ReviewFindingOutcomeRecord>()
  var producerOutputReadError: skillbill.ports.persistence.model.RejectedOutputDiagnosticError? = null
  private val diagnosticRecords =
    linkedMapOf<String, skillbill.ports.persistence.RejectedOutputDiagnosticRecord>()
  private val producerEvidence =
    linkedMapOf<ProducerEvidenceKey, skillbill.ports.persistence.ProducerOutputEvidence>()
  private val auditGenerationRows = repository.auditGenerationRows
  private val reviewsPort: ReviewRepository = harnessReviewRepository()
  private val learningsPort: LearningRepository = noopPort(LearningRepository::class.java)
  private val telemetryReconciliationPort: TelemetryReconciliationRepository =
    noopPort(TelemetryReconciliationRepository::class.java)
  private val telemetryOutboxPort: TelemetryOutboxRepository = noopPort(TelemetryOutboxRepository::class.java)

  fun auditGenerations(workflowId: String): List<FeatureTaskRuntimeAuditGenerationRow> =
    auditGenerationRows.filter { it.workflowId == workflowId }.sortedBy { it.generationOrdinal }

  fun rejectedDiagnostics(): List<skillbill.ports.persistence.RejectedOutputDiagnosticRecord> =
    diagnosticRecords.values.toList()

  fun retainedProducerEvidence(): List<skillbill.ports.persistence.ProducerOutputEvidence> =
    producerEvidence.values.toList()

  fun retainProducerEvidence(evidence: skillbill.ports.persistence.ProducerOutputEvidence) {
    unitOfWork().rejectedOutputDiagnostics!!.retainProducerOutput(evidence)
  }

  @Suppress("LongParameterList")
  fun producerEvidenceAt(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    generation: Int,
    agentId: String,
    repairTurn: Int = 0,
  ): skillbill.ports.persistence.ProducerOutputEvidence? =
    producerEvidence[ProducerEvidenceKey(workflowId, phaseId, generation, attempt, agentId, repairTurn)]

  override fun resolveDbPath(dbOverride: String?): Path = dbPath

  override fun databaseExists(dbOverride: String?): Boolean = true

  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(dbOverride: String?, block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> transaction(dbOverride: String?, block: (UnitOfWork) -> T): T {
    transactionDbOverrides += dbOverride
    return block(unitOfWork())
  }

  private fun unitOfWork(): UnitOfWork = object : UnitOfWork {
    override val dbPath: Path = this@RuntimeFakeDatabaseSessionFactory.dbPath
    override val reviews: ReviewRepository = this@RuntimeFakeDatabaseSessionFactory.reviewsPort
    override val learnings: LearningRepository = this@RuntimeFakeDatabaseSessionFactory.learningsPort
    override val lifecycleTelemetry: LifecycleTelemetryRepository = lifecycle
    override val telemetryReconciliation: TelemetryReconciliationRepository =
      this@RuntimeFakeDatabaseSessionFactory.telemetryReconciliationPort
    override val telemetryOutbox: TelemetryOutboxRepository = this@RuntimeFakeDatabaseSessionFactory.telemetryOutboxPort
    override val workflowStates: WorkflowStateRepository = repository

    // Mirrors the store's insert-only semantics: a duplicate ordinal is rejected rather than overwriting
    // durable history, so a test that re-appends a generation fails the same way production does.
    override val featureTaskRuntimeAuditGenerations =
      object : skillbill.ports.persistence.FeatureTaskRuntimeAuditGenerationRepository {
        override fun append(row: FeatureTaskRuntimeAuditGenerationRow) {
          require(
            auditGenerationRows.none {
              it.workflowId == row.workflowId && it.generationOrdinal == row.generationOrdinal
            },
          ) {
            "generation ${row.generationOrdinal} already exists for ${row.workflowId}"
          }
          auditGenerationRows += row
        }

        override fun listOrdered(workflowId: String): List<FeatureTaskRuntimeAuditGenerationRow> =
          auditGenerationRows.filter { it.workflowId == workflowId }.sortedBy { it.generationOrdinal }

        override fun quarantineAll(workflowId: String): Int {
          val removed = auditGenerationRows.count { it.workflowId == workflowId }
          auditGenerationRows.removeAll { it.workflowId == workflowId }
          return removed
        }
      }
    override val rejectedOutputDiagnosticPermissions =
      skillbill.ports.persistence.RejectedOutputDiagnosticPermissions { }
    override val rejectedOutputDiagnostics = object : skillbill.ports.persistence.RejectedOutputDiagnosticRepository {
      override fun insert(
        record: skillbill.ports.persistence.RejectedOutputDiagnosticRecord,
      ): skillbill.ports.persistence.RejectedOutputDiagnosticRecord =
        diagnosticRecords.getOrPut(record.metadata.identity) { record }

      override fun select(
        selector: skillbill.ports.persistence.RejectedOutputDiagnosticSelector,
      ): List<skillbill.ports.persistence.RejectedOutputDiagnostic> = diagnosticRecords.values
        .map { it.metadata }
        .filter {
          it.workflowId == selector.workflowId &&
            (selector.phaseId == null || it.phaseId == selector.phaseId) &&
            (selector.attempt == null || it.attempt == selector.attempt)
        }

      override fun read(identity: String): skillbill.ports.persistence.RejectedOutputDiagnosticRecord =
        diagnosticRecords[identity]
          ?: throw skillbill.ports.persistence.model.RejectedOutputDiagnosticError.Absent(identity)

      override fun markExpired(before: java.time.Instant): Int = 0

      override fun delete(selector: skillbill.ports.persistence.RejectedOutputDiagnosticSelector): Int = 0

      // Mirrors the SQLite write-once semantics: insert-if-absent, then a read-back equality guard
      // that raises Conflict on a divergent write to an already-retained key.
      override fun retainProducerOutput(evidence: skillbill.ports.persistence.ProducerOutputEvidence) {
        val key = ProducerEvidenceKey(
          evidence.workflowId,
          evidence.phaseId,
          evidence.generation,
          evidence.attempt,
          evidence.agentId,
          evidence.repairTurn,
        )
        producerEvidence.putIfAbsent(key, evidence)
        val retained = producerEvidence.getValue(key)
        if (retained.sha256 != evidence.sha256 || retained.byteSize != evidence.byteSize ||
          !samePayload(retained.payload, evidence.payload)
        ) {
          throw skillbill.ports.persistence.model.RejectedOutputDiagnosticError.Conflict(
            "${evidence.workflowId}:${evidence.phaseId}:${evidence.generation}:${evidence.attempt}:" +
              "${evidence.repairTurn}:${evidence.agentId}",
          )
        }
      }

      override fun readProducerOutput(
        workflowId: String,
        phaseId: String,
        attempt: Int,
        agentId: String,
        generation: Int,
      ): skillbill.ports.persistence.ProducerOutputEvidence? {
        producerOutputReadError?.let { throw it }
        return producerEvidence.entries
          .filter {
            it.key.workflowId == workflowId && it.key.phaseId == phaseId &&
              it.key.attempt == attempt && it.key.agentId == agentId &&
              it.key.generation <= generation
          }
          .maxWithOrNull(compareBy({ it.key.generation }, { it.key.repairTurn }))
          ?.value
      }
    }.takeIf { rejectedOutputDiagnosticsAvailable }
    override val unaddressedFindings = object : skillbill.ports.persistence.UnaddressedFindingsRepository {
      override fun replaceLedgerForPass(
        workflowId: String,
        reviewPassNumber: Int,
        findings: List<skillbill.goalrunner.model.UnaddressedFinding>,
      ) {
        ledgerRows.removeAll { it.workflowId == workflowId && it.reviewPassNumber <= reviewPassNumber }
        ledgerRows.addAll(findings)
      }

      override fun clearWorkflowLedger(workflowId: String) {
        ledgerRows.removeAll { it.workflowId == workflowId }
      }

      override fun fetchLedger(issueKey: String): List<skillbill.goalrunner.model.UnaddressedFinding> =
        ledgerRows.filter { it.issueKey == issueKey }

      override fun fetchWorkflowLedger(workflowId: String): List<skillbill.goalrunner.model.UnaddressedFinding> =
        ledgerRows.filter { it.workflowId == workflowId }

      override fun workflowIdsForIssue(issueKey: String): List<String> =
        ledgerRows.filter { it.issueKey == issueKey }.map { it.workflowId }.distinct().sorted()

      override fun recordOutcomes(outcomes: List<skillbill.goalrunner.model.ReviewFindingOutcomeRecord>) {
        outcomeRows.removeAll { existing ->
          outcomes.any {
            it.workflowId == existing.workflowId &&
              it.reviewPassNumber == existing.reviewPassNumber &&
              it.findingOrdinal == existing.findingOrdinal
          }
        }
        outcomeRows.addAll(outcomes)
      }

      override fun fetchOutcomes(workflowId: String): List<skillbill.goalrunner.model.ReviewFindingOutcomeRecord> =
        outcomeRows.filter { it.workflowId == workflowId }

      override fun issueExists(issueKey: String): Boolean = knownIssue
    }
    override val workList = skillbill.ports.persistence.EmptyWorkListRepository
    override val goalPlanningPreparations = skillbill.ports.persistence.EmptyGoalPlanningPreparationRepository
  }
}

private object EnabledRuntimeTelemetrySettingsProvider : TelemetrySettingsProvider {
  override fun load(materialize: Boolean): TelemetrySettings = TelemetrySettings(
    configPath = Path.of("/fake/config.json"),
    level = "full",
    enabled = true,
    installId = "install-1",
    proxyUrl = "",
    customProxyUrl = null,
    batchSize = 50,
  )
}

@Suppress("TooManyFunctions") // mirrors the full LifecycleTelemetryRepository contract
internal class RecordingLifecycleTelemetryRepository(
  private val throwOnDiagnosticDegradation: Boolean = false,
) : LifecycleTelemetryRepository {
  val startedRecords = mutableListOf<FeatureTaskRuntimeStartedRecord>()
  val finishedRecords = mutableListOf<FeatureTaskRuntimeFinishedRecord>()
  val sharedEvidenceMeasurements =
    mutableListOf<skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement>()
  val diagnosticDegradationMeasurements =
    mutableListOf<skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement>()

  override fun featureTaskRuntimeSharedEvidence(
    record: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement,
  ) {
    sharedEvidenceMeasurements += record
  }

  override fun featureTaskRuntimeDiagnosticDegradation(
    record: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement,
  ) {
    if (throwOnDiagnosticDegradation) error("telemetry sink failed")
    diagnosticDegradationMeasurements += record
  }

  override fun featureTaskRuntimeStarted(record: FeatureTaskRuntimeStartedRecord, level: String) {
    startedRecords += record
  }

  override fun featureTaskRuntimeFinished(record: FeatureTaskRuntimeFinishedRecord, level: String) {
    finishedRecords += record
  }

  override fun qualityCheckStarted(record: skillbill.telemetry.model.QualityCheckStartedRecord, level: String) = Unit

  override fun qualityCheckFinished(record: skillbill.telemetry.model.QualityCheckFinishedRecord, level: String) = Unit

  override fun featureVerifyStarted(record: skillbill.telemetry.model.FeatureVerifyStartedRecord, level: String) = Unit

  override fun featureVerifyFinished(record: skillbill.telemetry.model.FeatureVerifyFinishedRecord, level: String) =
    Unit

  override fun prDescriptionGenerated(record: skillbill.telemetry.model.PrDescriptionGeneratedRecord, level: String) =
    Unit

  override fun goalStarted(record: skillbill.telemetry.model.GoalStartedRecord, level: String) = Unit

  override fun goalSubtaskFinished(record: skillbill.telemetry.model.GoalSubtaskFinishedRecord, level: String) = Unit

  override fun goalFinished(record: skillbill.telemetry.model.GoalFinishedRecord, level: String) = Unit

  override fun goalIssueFinished(record: skillbill.telemetry.model.GoalIssueFinishedRecord, level: String) = Unit
}

private fun FeatureTaskRuntimeWorkerOwnership.matchesActiveOwnership(
  workflowId: String,
  ownerToken: String,
  generation: Long,
): Boolean = this.workflowId == workflowId &&
  this.ownerToken == ownerToken &&
  this.generation == generation &&
  leaseState == FeatureTaskRuntimeWorkerLeaseState.ACTIVE

internal class InMemoryRuntimeWorkflowRepository : WorkflowStateRepository {
  private var workerOwnership: FeatureTaskRuntimeWorkerOwnership? = null

  /**
   * Append-only audit-generation history, held here rather than on the session factory because a resume is
   * modelled by building a fresh factory over this same repository. On the factory it would model a database
   * that forgot its audit history at every restart, and a repair settlement would then be the first
   * generation.
   */
  val auditGenerationRows = mutableListOf<FeatureTaskRuntimeAuditGenerationRow>()

  fun seedWorkerOwnership(ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership) {
    workerOwnership = ownership
  }

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String) =
    synchronized(this) { workerOwnership?.takeIf { it.workflowId == workflowId } }

  override fun acquireFeatureTaskRuntimeWorker(
    ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership,
    expectedUpdatedAt: String?,
  ): Boolean = synchronized(this) {
    if (workerOwnership != null || taskRuntimeRows[ownership.workflowId]?.updatedAt != expectedUpdatedAt) return false
    workerOwnership = ownership
    true
  }

  override fun reserveFeatureTaskRuntimeWorkerTakeover(
    workflowId: String,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = synchronized(this) {
    val current = workerOwnership ?: return false
    if (!current.matchesActiveOwnership(workflowId, expectedOwnerToken, expectedGeneration)) return false
    workerOwnership = current.copy(
      leaseState = skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED,
    )
    true
  }

  override fun transferFeatureTaskRuntimeWorker(
    ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = synchronized(this) {
    val current = workerOwnership ?: return false
    if (
      current.ownerToken != expectedOwnerToken || current.generation != expectedGeneration ||
      current.leaseState != skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED
    ) {
      return false
    }
    workerOwnership = ownership
    true
  }

  override fun heartbeatFeatureTaskRuntimeWorker(
    ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership,
  ): Boolean = synchronized(this) {
    val current = workerOwnership ?: return false
    if (current.ownerToken != ownership.ownerToken || current.generation != ownership.generation) return false
    workerOwnership = ownership
    true
  }

  override fun releaseFeatureTaskRuntimeWorker(workflowId: String, ownerToken: String, generation: Long): Boolean =
    synchronized(this) {
      val current = workerOwnership ?: return false
      if (current.workflowId != workflowId || current.ownerToken != ownerToken || current.generation != generation) {
        return false
      }
      workerOwnership = null
      true
    }
  override fun findFeatureTaskRuntimeCrashReconciliationCandidates(
    nowInstant: String,
  ): List<skillbill.ports.persistence.model.FeatureTaskRuntimeCrashReconciliationCandidate> = synchronized(this) {
    val ownership = workerOwnership ?: return@synchronized emptyList()
    val row = taskRuntimeRows[ownership.workflowId] ?: return@synchronized emptyList()
    if (row.workflowStatus != "running" || !leaseExpiredBefore(ownership.expiresAt, nowInstant)) {
      return@synchronized emptyList()
    }
    listOf(
      skillbill.ports.persistence.model.FeatureTaskRuntimeCrashReconciliationCandidate(
        ownership = ownership,
        currentStepId = row.currentStepId,
        workflowStatus = row.workflowStatus,
      ),
    )
  }

  override fun reconcileFeatureTaskRuntimeCrashedWorker(
    workflowId: String,
    ownerToken: String,
    generation: Long,
    interruptionReason: String,
    nowInstant: String,
  ): Boolean = synchronized(this) {
    val current = workerOwnership ?: return@synchronized false
    if (current.workflowId != workflowId || current.ownerToken != ownerToken || current.generation != generation) {
      return@synchronized false
    }
    if (!leaseExpiredBefore(current.expiresAt, nowInstant)) return@synchronized false
    val row = taskRuntimeRows[workflowId] ?: return@synchronized false
    if (row.workflowStatus != "running") return@synchronized false
    workerOwnership = null
    taskRuntimeRows[workflowId] = row.copy(workflowStatus = "pending")
    reconciledInterruptionReasons[workflowId] = interruptionReason
    true
  }

  val reconciledInterruptionReasons = linkedMapOf<String, String>()

  private fun leaseExpiredBefore(expiresAt: String, nowInstant: String): Boolean =
    runCatching { java.time.Instant.parse(expiresAt).isBefore(java.time.Instant.parse(nowInstant)) }
      .getOrDefault(false)

  override fun saveFeatureTaskExecutionIdentity(
    identity: skillbill.ports.persistence.model.FeatureTaskExecutionIdentity,
  ) = Unit

  override fun findStandaloneFeatureTaskCandidates(normalizedIssueKey: String, repositoryIdentity: String) =
    emptyList<skillbill.ports.persistence.model.FeatureTaskWorkflowCandidate>()

  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()

  // Prose feature-task rows route through the legacy prose family store (see WorkflowStateRepository).
  private val implementRows = linkedMapOf<String, WorkflowStateRecord>()

  fun taskRuntimeArtifacts(workflowId: String): Map<String, Any?> {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    return skillbill.contracts.JsonSupport.parseObjectOrNull(record.artifactsJson)
      ?.let(skillbill.contracts.JsonSupport::jsonElementToValue)
      ?.let(skillbill.contracts.JsonSupport::anyToStringAnyMap)
      .orEmpty()
  }

  // Overwrites the per-phase records key with a present-but-non-map blob to
  // simulate corrupt durable state.
  fun corruptRecordsArtifact(workflowId: String, corruptValue: Any?) {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    val artifacts = LinkedHashMap(taskRuntimeArtifacts(workflowId)).apply {
      put(FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY, corruptValue)
    }
    taskRuntimeRows[workflowId] = record.copy(
      artifactsJson = skillbill.contracts.JsonSupport.mapToJsonString(artifacts),
    )
  }

  fun replaceTaskRuntimeArtifacts(workflowId: String, artifacts: Map<String, Any?>) {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    taskRuntimeRows[workflowId] = record.copy(
      artifactsJson = skillbill.contracts.JsonSupport.mapToJsonString(artifacts),
    )
  }

  // Fault injection for atomicity coverage: when this predicate matches the row about to be written,
  // the single save carrying both the artifact patch and the workflow advance fails, standing in for a
  // process killed at that instant.
  var failSaveWhen: ((WorkflowStateRecord) -> Boolean)? = null

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    if (failSaveWhen?.invoke(row) == true) {
      error("simulated process kill during the feature-task-runtime save")
    }
    taskRuntimeRows[row.workflowId] = row
  }

  fun bumpUpdatedAt(workflowId: String) {
    val row = taskRuntimeRows[workflowId] ?: return
    val current = java.time.Instant.parse(row.updatedAt ?: "2026-01-01T00:00:00Z")
    taskRuntimeRows[workflowId] = row.copy(updatedAt = current.plusSeconds(1).toString())
  }

  override fun getFeatureTaskRuntimeWorkflow(workflowId: String): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskRuntimeWorkflows(limit: Int): List<WorkflowStateRecord> =
    taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun latestFeatureTaskRuntimeWorkflow(): WorkflowStateRecord? =
    listFeatureTaskRuntimeWorkflows(1).firstOrNull()

  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    implementRows[row.workflowId] = row
  }

  override fun saveFeatureVerifyWorkflow(row: WorkflowStateRecord) = Unit

  override fun getFeatureImplementWorkflow(workflowId: String): WorkflowStateRecord? = implementRows[workflowId]

  override fun getFeatureVerifyWorkflow(workflowId: String): WorkflowStateRecord? = null

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun listFeatureVerifyWorkflows(limit: Int): List<WorkflowStateRecord> = emptyList()

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = null

  override fun latestFeatureVerifyWorkflow(): WorkflowStateRecord? = null

  override fun getFeatureImplementSessionSummary(sessionId: String): FeatureImplementSessionSummary? = null

  override fun getFeatureVerifySessionSummary(sessionId: String): FeatureVerifySessionSummary? = null
}

// Reports every inspected worker process as dead, so a seeded crashed row with an expired lease is a
// confirmed crash-reconciliation candidate. Harness default: benign when no candidate is seeded.
internal object HarnessDeadProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("harness-host", "harness-boot", 4321, "harness-birth-4321")

  override fun inspect(ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership) =
    FeatureTaskRuntimeProcessInspection.NotRunning

  override fun terminateGracefully(ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership) =
    true

  override fun terminateForcibly(ownership: skillbill.ports.persistence.model.FeatureTaskRuntimeWorkerOwnership) = true

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ) = NoopFeatureTaskRuntimeHeartbeat

  override fun pause(durationMillis: Long) = Unit
}

/**
 * AC-007: SKILL-15 shape — review generation 0 attempt 2 under one agent, then a different
 * producer retains different bytes for the same generation/attempt. Pre-fix four-part-key
 * Conflict would abort phase recording; agent-scoped identity must continue.
 */
class FeatureTaskRuntimeProducerEvidenceIdentityTest {
  @Test
  fun `cross-agent retain at review generation 0 attempt 2 continues without Conflict`() {
    val harness = runnerHarness()
    val claudePayload = "claude-skill15-review-0-2".encodeToByteArray()
    val cursorPayload = "cursor-skill15-review-0-2".encodeToByteArray()
    val recordedAt = java.time.Instant.parse("2026-08-08T18:49:48Z")

    harness.io.database.retainProducerEvidence(
      skillbill.ports.persistence.ProducerOutputEvidence(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        attempt = 2,
        agentId = "claude",
        model = "claude-opus",
        recordedAt = recordedAt,
        byteSize = claudePayload.size.toLong(),
        sha256 = skillbill.application.featuretask.RejectedOutputDiagnosticService.sha256(claudePayload),
        payload = claudePayload,
        generation = 0,
      ),
    )

    harness.io.database.retainProducerEvidence(
      skillbill.ports.persistence.ProducerOutputEvidence(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        attempt = 2,
        agentId = "cursor",
        model = "gpt",
        recordedAt = recordedAt.plusSeconds(60),
        byteSize = cursorPayload.size.toLong(),
        sha256 = skillbill.application.featuretask.RejectedOutputDiagnosticService.sha256(cursorPayload),
        payload = cursorPayload,
        generation = 0,
      ),
    )

    assertContentEquals(
      claudePayload,
      harness.io.database.producerEvidenceAt(WORKFLOW_ID, "review", 2, 0, "claude")?.payload,
    )
    assertContentEquals(
      cursorPayload,
      harness.io.database.producerEvidenceAt(WORKFLOW_ID, "review", 2, 0, "cursor")?.payload,
    )
  }

  @Test
  fun `same-agent divergent retain at a reused key still Conflicts`() {
    val harness = runnerHarness()
    val first = "first".encodeToByteArray()
    val second = "second".encodeToByteArray()
    val recordedAt = java.time.Instant.parse("2026-08-08T18:49:48Z")
    harness.io.database.retainProducerEvidence(
      skillbill.ports.persistence.ProducerOutputEvidence(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        attempt = 2,
        agentId = "claude",
        model = "claude-opus",
        recordedAt = recordedAt,
        byteSize = first.size.toLong(),
        sha256 = skillbill.application.featuretask.RejectedOutputDiagnosticService.sha256(first),
        payload = first,
        generation = 0,
      ),
    )

    assertFailsWith<skillbill.ports.persistence.model.RejectedOutputDiagnosticError.Conflict> {
      harness.io.database.retainProducerEvidence(
        skillbill.ports.persistence.ProducerOutputEvidence(
          workflowId = WORKFLOW_ID,
          phaseId = "review",
          attempt = 2,
          agentId = "claude",
          model = "claude-opus",
          recordedAt = recordedAt.plusSeconds(1),
          byteSize = second.size.toLong(),
          sha256 = skillbill.application.featuretask.RejectedOutputDiagnosticService.sha256(second),
          payload = second,
          generation = 0,
        ),
      )
    }
    assertContentEquals(
      first,
      harness.io.database.producerEvidenceAt(WORKFLOW_ID, "review", 2, 0, "claude")?.payload,
    )
  }
}

class InfraFailureReasonStderrSurfacingTest {
  @Test
  fun `non-zero exit with non-blank stderr surfaces a bounded stderr excerpt in blocked reason`() {
    val stderrContent = "Error: something went wrong with the child process"
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CLAUDE,
          exitStatus = 1,
          stdout = "",
          stderr = stderrContent,
          timedOut = false,
          spawnFailed = false,
        )
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "exited with non-zero status 1")
    assertContains(blocked.blockedReason, stderrContent)
  }

  @Test
  fun `non-zero exit with blank stderr and non-blank stdout surfaces stdout excerpt in blocked reason`() {
    val stdoutContent = "unexpected non-json output from the child"
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CLAUDE,
          exitStatus = 2,
          stdout = stdoutContent,
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "exited with non-zero status 2")
    assertContains(blocked.blockedReason, stdoutContent)
  }

  @Test
  fun `non-zero exit with both blank stderr and stdout does not append an excerpt to blocked reason`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CLAUDE,
          exitStatus = 1,
          stdout = "",
          stderr = "",
          timedOut = false,
          spawnFailed = false,
        )
      },
    )

    val report = harness.runner.run(harness.request())

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report)
    assertContains(blocked.blockedReason, "exited with non-zero status 1")
    assertFalse(
      blocked.blockedReason.contains("\n"),
      "reason must not contain a newline when no output is available: '${blocked.blockedReason}'",
    )
  }
}

/**
 * The blocked run this suite exists for reported `validate` as having "exhausted the bounded fix
 * loop ... rather than advancing on invalid output" after three launches eleven seconds apart. The
 * phase had produced no output at all: the provider had refused every launch at a session limit, and
 * each crash resume spent one semantic repair attempt. A limit refusal now pauses, a process failure
 * is charged to its own budget, and an operator reopen outranks both.
 */
class ProviderLimitAndProcessFailureBudgetTest {
  private fun limitFacts() = AgentRunLaunchFacts(
    agent = InstallAgent.CLAUDE,
    exitStatus = 1,
    stdout = "",
    stderr = "You've hit your session limit · resets 3:40am (Europe/Berlin)",
    timedOut = false,
    spawnFailed = false,
  )

  @Test
  fun `a provider usage limit pauses the phase instead of spending a repair attempt`() {
    val harness = runnerHarness(launcher = RuntimeRecordingLauncher { limitFacts() })

    val paused = assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request()))

    assertEquals("preplan", paused.pausedPhase)
    assertEquals("preplan", paused.resumableStep)
    assertContains(paused.pauseReason, "refused the request at a usage limit")
    assertContains(paused.pauseReason, "Access resets 3:40am (Europe/Berlin).")
    assertContains(paused.pauseReason, "consumed no repair attempt")
    assertContains(paused.pauseReason, "You've hit your session limit")
    assertTrue(
      !paused.pauseReason.contains("invalid output") && !paused.pauseReason.contains("fix loop"),
      "the limit refusal produced no output to call invalid: '${paused.pauseReason}'",
    )
  }

  @Test
  fun `the paused phase stays resumable and never accumulates a fix-loop block`() {
    val harness = runnerHarness(launcher = RuntimeRecordingLauncher { limitFacts() })

    repeat(4) { assertIs<FeatureTaskRuntimeRunReport.Paused>(harness.runner.run(harness.request())) }

    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()["preplan"])
    assertEquals("paused", record.status)
    assertEquals(FeatureTaskRuntimeFailureDisposition.RETRYABLE, record.failureDisposition)
    assertEquals(4, harness.launcher.requests.size, "every resume must relaunch, not re-surface a block")
  }

  @Test
  fun `repeated process failures block on the process budget with an accurate reason`() {
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher {
        AgentRunLaunchFacts(
          agent = InstallAgent.CLAUDE,
          exitStatus = 1,
          stdout = "",
          stderr = "Error: the child process died",
          timedOut = false,
          spawnFailed = false,
        )
      },
    )

    repeat(FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS) {
      val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
      assertContains(blocked.blockedReason, "exited with non-zero status 1")
    }

    val exhausted = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    assertContains(exhausted.blockedReason, "failed to execute")
    assertContains(exhausted.blockedReason, "No repair attempt was consumed.")
    assertTrue(
      !exhausted.blockedReason.contains("bounded fix loop"),
      "a process that never reached the output gate produced no invalid output: '${exhausted.blockedReason}'",
    )
    assertEquals(
      FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS,
      harness.launcher.requests.size,
      "the process budget bounds relaunches; the exhausted run must not launch again",
    )
  }

  /**
   * The stuck state SKILL-136 was left in: `validate` carried a durable attempt count past the cap,
   * so every resume re-blocked at the budget gate without relaunching anything and the operator's
   * reopen was a no-op.
   */
  @Test
  fun `an operator reopen relaunches a phase whose durable attempt count is past the cap`() {
    var launchValidOutput = false
    val harness = runnerHarness(
      launcher = RuntimeRecordingLauncher { request ->
        if (!launchValidOutput) {
          return@RuntimeRecordingLauncher AgentRunLaunchFacts(
            agent = InstallAgent.CLAUDE,
            exitStatus = 1,
            stdout = "",
            stderr = "Error: the child process died",
            timedOut = false,
            spawnFailed = false,
          )
        }
        facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
      },
    )

    repeat(FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS) { harness.runner.run(harness.request()) }
    val exhausted = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))
    val stuckPhase = requireNotNull(exhausted.lastIncompletePhase)
    val launchesBeforeReopen = harness.launcher.requests.size

    reopenPhaseAsOperator(harness, stuckPhase)
    launchValidOutput = true

    harness.runner.run(harness.request())

    assertTrue(
      harness.launcher.requests.size > launchesBeforeReopen,
      "the reopened phase must actually relaunch instead of re-surfacing the block the operator acted on",
    )
    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty()[stuckPhase])
    assertEquals("completed", record.status)
  }

  // Mirrors what `feature-task-runtime retry-blocked` persists: the phase record reopens to pending
  // with its attempt count intact, alongside the operator's reason.
  private fun reopenPhaseAsOperator(harness: RunnerHarness, phaseId: String) {
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    val records = skillbill.contracts.JsonSupport
      .anyToStringAnyMap(artifacts["feature_task_runtime_phase_records"])
      .orEmpty()
      .toMutableMap()
    val record = requireNotNull(skillbill.contracts.JsonSupport.anyToStringAnyMap(records[phaseId]))
      .toMutableMap()
    record["status"] = "pending"
    record["blocked_reason"] = null
    records[phaseId] = record
    artifacts["feature_task_runtime_phase_records"] = records
    artifacts["operator_block_retry"] = mapOf<String, Any?>(
      "phase_id" to phaseId,
      "reason" to "operator applied the fix out of band",
      "retried_at" to "2026-08-06T00:00:00Z",
    )
    // The grant is only live while a RETRY ledger entry stands unsettled, so the fixture appends the
    // same entry the retry-blocked command writes.
    val ledger = (artifacts["feature_task_runtime_phase_ledger"] as? List<*>).orEmpty()
    val nextSequence = ledger
      .mapNotNull { skillbill.contracts.JsonSupport.anyToStringAnyMap(it)?.get("sequence_number") as? Number }
      .maxOfOrNull { it.toInt() + 1 } ?: 0
    artifacts["feature_task_runtime_phase_ledger"] = ledger + mapOf<String, Any?>(
      "action" to "retry",
      "sequence_number" to nextSequence,
      "timestamp" to "2026-08-06T00:00:00Z",
      "phase_id" to phaseId,
      "attempt_count" to (record["attempt_count"] ?: 1),
    )
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }

  @Test
  fun `a phase that does reach its output gate is still bounded by the output budgets`() {
    val harness = runnerHarness(launcher = RuntimeRecordingLauncher { facts("not json") })

    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(harness.runner.run(harness.request()))

    assertTrue(
      !blocked.blockedReason.contains("failed to execute"),
      "output that reached the gate is not a process failure: '${blocked.blockedReason}'",
    )
  }
}

class FeatureTaskRuntimeReservedPassLedgerRecoveryTest {
  @Test
  fun `recovering a reserved pass settles review state and its ledger rows together`() {
    var crashOnFix = true
    val harness = runnerHarness(
      agentAssignment = phasePerAgentAssignment(),
      runtimeConfig = RuntimeHarnessConfig(
        branchSetup = committedRepoBranchSetup(),
        goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = ISSUE_KEY,
          subtaskId = 5,
          goalBranch = "feat/existing-runtime-branch",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        ),
        reviewDriver = reviewFixDriver(2),
      ),
      launcher = RuntimeRecordingLauncher { request ->
        val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
        if (phaseId == "implement_fix" && crashOnFix) spawnFailedFacts() else facts(validJsonOutput(phaseId))
      },
    )

    assertIs<FeatureTaskRuntimeRunReport.Blocked>(
      harness.runner.run(
        harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
      ),
    )
    val reserved = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, reserved.completedPassCount)
    assertEquals(null, reserved.reservedPassNumber)

    crashOnFix = false
    assertIs<FeatureTaskRuntimeRunReport.Completed>(
      harness.runner.run(
        harness.request().copy(requestedCodeReviewMode = CodeReviewExecutionMode.INLINE),
      ),
    )
    val recovered = requireNotNull(harness.goalContinuationRecorder.reviewState(WORKFLOW_ID))
    assertEquals(1, recovered.completedPassCount)
    assertEquals(null, recovered.reservedPassNumber)
    assertFullyAssociatedLedgerRows(harness, passNumber = 1, subtaskId = 5)
  }
}

private fun assertFullyAssociatedLedgerRows(harness: RunnerHarness, passNumber: Int, subtaskId: Int) {
  val rows = harness.ledgerRows.filter { it.reviewPassNumber == passNumber }
  assertTrue(rows.isNotEmpty(), "pass $passNumber must settle its ledger rows")
  rows.forEach { row ->
    assertEquals(ISSUE_KEY, row.issueKey)
    assertEquals(subtaskId, row.subtaskId)
    assertEquals(WORKFLOW_ID, row.workflowId)
    assertTrue(row.severity.isNotBlank())
    assertTrue(row.issueCategory.isNotBlank())
    assertTrue(row.location.isNotBlank())
    assertTrue(row.summary.isNotBlank())
  }
}
