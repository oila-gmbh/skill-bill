package skillbill.application

import skillbill.application.featurespec.FeatureSpecPreparationRuntime
import skillbill.application.featurespec.FeatureSpecPreparationWriter
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator
import skillbill.application.featuretask.AcceptingFeatureTaskRuntimeHandoffFoundationValidator
import skillbill.application.featuretask.FeatureTaskPhaseSettlementService
import skillbill.application.featuretask.FeatureTaskRuntimeBranchSetupRunner
import skillbill.application.featuretask.FeatureTaskRuntimeCrashReconciler
import skillbill.application.featuretask.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeDecompositionPlanner
import skillbill.application.featuretask.FeatureTaskRuntimeFindingVerificationBoundaryMemory
import skillbill.application.featuretask.FeatureTaskRuntimeGoalContinuationRecorder
import skillbill.application.featuretask.FeatureTaskRuntimeLifecycleTelemetry
import skillbill.application.featuretask.FeatureTaskRuntimePhaseGates
import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.FeatureTaskRuntimePlanningStopper
import skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver
import skillbill.application.featuretask.FeatureTaskRuntimeRunInvariantsStore
import skillbill.application.featuretask.FeatureTaskRuntimeRunner
import skillbill.application.featuretask.FeatureTaskRuntimeSpecGate
import skillbill.application.featuretask.InMemoryFeatureTaskPhaseSettlementRepository
import skillbill.application.featuretask.featureTaskRuntimePhaseRecorder
import skillbill.application.featuretask.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.featuretask.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseGateDependencies
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEvent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunEventSink
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunnerDependencies
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateProgressStore
import skillbill.application.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator
import skillbill.application.featuretask.validation.FeatureTaskRuntimeValidationGateProgressStore
import skillbill.application.featuretask.validation.ValidationGateResolver
import skillbill.application.review.SpecIntentProjectionExtractor
import skillbill.application.review.SpecIntentProjectionResolver
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.application.specsource.SpecSourceResolver
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.workflow.repoRoot
import skillbill.config.model.RepoLocalConfig
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.goalrunner.model.ReviewFindingOutcomeRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.UnitOfWork
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RejectedOutputDiagnosticPermissions
import skillbill.ports.diagnostics.RejectedOutputDiagnosticRepository
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError.Absent
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticError.Conflict
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticRecord
import skillbill.ports.diagnostics.model.RejectedOutputDiagnosticSelector
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.featuretask.FeatureTaskRuntimeAuditGenerationRepository
import skillbill.ports.featuretask.model.FeatureTaskExecutionIdentity
import skillbill.ports.featuretask.model.FeatureTaskRuntimeAuditGenerationRow
import skillbill.ports.featuretask.model.FeatureTaskRuntimeCrashReconciliationCandidate
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.featuretask.model.FeatureTaskWorkflowCandidate
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.UnaddressedFindingsRepository
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.learning.LearningRepository
import skillbill.ports.review.ReviewRepository
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSpecStatusWriter
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.telemetry.LifecycleTelemetryRepository
import skillbill.ports.telemetry.TelemetryOutboxRepository
import skillbill.ports.telemetry.TelemetryReconciliationRepository
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateCacheMode.CACHE_ELIGIBLE
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunOutcome.FAILED
import skillbill.ports.validation.model.ValidationGateRunOutcome.PASSED
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperationsProvider
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperationsProvider
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperationsProvider
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperationsProvider
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperationsProvider
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.model.FeatureImplementSessionSummary
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode.PROSE
import skillbill.ports.workflow.model.FeatureVerifySessionSummary
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.specscratch.SpecScratchStore
import skillbill.review.context.ReviewContextEnvelopeValidator
import skillbill.review.context.model.ReviewContextBudgetExceeded
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity.BLOCKER
import skillbill.review.model.ReviewFindingVerdict
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat.JUNIT_XML
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.telemetry.model.TelemetrySettings
import skillbill.workflow.engine.WorkflowSnapshotValidator
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.FeatureTaskRuntimeBuildReceiptValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimeBuildReceiptValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputValidationResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import java.lang.Boolean.TYPE
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.lang.Double.TYPE as DoubleTYPE
import java.lang.Long.TYPE as LongTYPE

internal const val WORKFLOW_ID = "wftr-20260602-test-0001"
internal const val SESSION_ID = "ftr-test-001"
internal const val RUNNER_TEST_ISSUE_KEY = "SKILL-65"
private const val ISSUE_KEY = RUNNER_TEST_ISSUE_KEY
internal const val RUNNER_TEST_SPEC_REFERENCE = ".feature-specs/SKILL-65/spec.md"
internal const val SPEC_REFERENCE = RUNNER_TEST_SPEC_REFERENCE
internal const val CONVENTION_SPEC_REFERENCE =
  ".feature-specs/SKILL-65-runtime-feature-task-parity/spec_subtask_1.md"
internal const val EXPECTED_FEATURE_BRANCH = "feat/SKILL-65-runtime-feature-task-parity"
internal const val INVOKED_AGENT = "claude-code"
internal const val VALID_OUTPUT = """{"contract_version":"0.2"}"""
internal val VALIDATE_REPAIR_WITHOUT_GATE_COUNTS = """
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
      }
    }
  }
""".trimIndent()

internal fun failThenPassValidationGateRunner(gateCalls: AtomicInteger): ValidationGateRunner =
  object : ValidationGateRunner {
    override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
      val call = gateCalls.getAndIncrement()
      val outcome = if (call == 0) {
        FAILED
      } else {
        PASSED
      }
      return ValidationGateRunResult(
        exitCode = if (call == 0) 1 else 0,
        durationMs = 1,
        outcome = outcome,
        cacheMode = if (call == 0) {
          CACHE_ELIGIBLE
        } else {
          request.cacheMode
        },
        executedWorkUnits = 1,
        findings = if (call == 0) {
          listOf(
            ValidationGateFinding("app", "t", "broken", "A.kt"),
          )
        } else {
          emptyList()
        },
      )
    }
  }

internal fun kotlinPackWithValidationGate(): PlatformManifest = PlatformManifest(
  slug = "kotlin",
  packRoot = Path.of("/tmp/repo/platform-packs/kotlin"),
  contractVersion = "1.7",
  routingSignals = RoutingSignals(
    strong = listOf("src"),
    tieBreakers = emptyList(),
    path = listOf("src"),
  ),
  declaredCodeReviewAreas = emptyList(),
  declaredFiles = DeclaredFiles(null, emptyMap()),
  areaMetadata = emptyMap(),
  validationGate = ValidationGateDeclaration(
    fullGateCommand = listOf("echo", "cache"),
    cacheBypassingFullGateCommand = listOf("echo", "full"),
    collectAllFullGateCommand = listOf("echo", "collect-all"),
    cacheBypassingCollectAllFullGateCommand = listOf("echo", "collect-all-full"),
    findings = ValidationGateFindingsLocator(
      format = JUNIT_XML,
      artifactGlobs = listOf("**/*.xml"),
      compilerDiagnostics = ValidationGateCompilerDiagnosticsLocator(
        GRADLE_KOTLIN_COMPILER_STDOUT,
      ),
      executedWork = ValidationGateExecutedWorkSignal(
        GRADLE_ACTIONABLE_SUMMARY,
      ),
    ),
  ),
)
internal const val VALID_REVIEW_OUTPUT = """{"contract_version":"0.3","produced_outputs":{"findings":[]}}"""

internal const val VALID_AUDIT_OUTPUT =
  """{"contract_version":"0.6","phase_id":"audit","status":"completed","summary":"Audit satisfied.",""" +
    """"verdict":"satisfied","produced_outputs":{"value":"{\"gaps\":[],\"non_blocking_findings\":[]}"}}"""

internal val VALID_VERIFY_FINDINGS_OUTPUT = verifyFindingsOutput()
internal val PREPLAN_OUTPUT = seededProjectionEnvelope("preplan", PlanningProjectionFixtures.PREPLAN_DIGEST)
internal val PLAN_OUTPUT = seededProjectionEnvelope("plan", PlanningProjectionFixtures.PLAN_PROSE)
internal val IMPLEMENT_OUTPUT =
  seededProjectionEnvelope("implement", PlanningProjectionFixtures.IMPLEMENT_PROSE)

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
internal fun expiredCrashedOwnership(): FeatureTaskRuntimeWorkerOwnership = FeatureTaskRuntimeWorkerOwnership(
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
internal fun phaseAgent(phaseId: String): String = "agent-$phaseId"

internal fun phasePerAgentAssignment(): FeatureTaskRuntimeAgentAssignment =
  FeatureTaskRuntimeAgentAssignment(perPhaseAgentIds = ALL_PHASES.associateWith(::phaseAgent))
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

internal data class SeedReentryPhaseSeed(
  val phaseId: String,
  val status: String,
  val attemptCount: Int,
  val agentId: String,
  val outputArtifact: String?,
  val loopId: String,
  val edgeIteration: Int,
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
  val ledgerRows: List<UnaddressedFinding> get() = io.database.ledgerRows
  fun seedRawReviewResults(state: GoalSubtaskReviewState) {
    val artifacts = repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY] = state.passResults.associate { result ->
      result.passNumber.toString() to "raw review result for pass ${result.passNumber}"
    }
    repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }

  fun reviewedDeltaDigest(): String? =
    requireNotNull(goalContinuationRecorder.reviewStateRecorder.reviewState(WORKFLOW_ID)).reviewedDeltaDigest

  fun currentReviewDeltaDigest(git: RecordingWorkflowGitOperations, repoRoot: Path): String {
    val state = requireNotNull(goalContinuationRecorder.reviewStateRecorder.reviewState(WORKFLOW_ID))
    return requireNotNull(
      git.buildGoalSubtaskReviewInput(
        repoRoot,
        GoalSubtaskReviewBaseline(state.reviewBaseSha, state.baselineUntrackedPaths),
        "feat/existing-runtime-branch",
      ).input,
    ).deltaDigest
  }
  fun stripReviewedDeltaDigest() {
    val artifacts = repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    val state = JsonSupport
      .anyToStringAnyMap(artifacts[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY])
      .orEmpty()
      .toMutableMap()
    state.remove("reviewed_delta_digest")
    artifacts[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY] = state
    repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }
  fun launchOrder(): List<String> = events.mapNotNull { event ->
    when (event) {
      is FeatureTaskRuntimeRunEvent.PhaseStarted -> event.phaseId
      is FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration -> event.phaseId
      else -> null
    }
  }
  fun launchedPhaseOrder(): List<String> = launcher.requests.map { request ->
    ALL_PHASES.firstOrNull { phaseId -> phaseAgent(phaseId) == request.invokedAgentId }
      ?: error("Launch request agent '${request.invokedAgentId}' is not phase-attributable.")
  }
  fun launchedPromptPhaseOrder(): List<String> = launcher.requests.map { request ->
    phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
  }
  fun seedPhase(phaseId: String, status: String, attemptCount: Int, agentId: String, outputArtifact: String?) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseStateForTest(phaseId, status, attemptCount, agentId, outputArtifact)
  }

  fun seedReviewPhase(status: String, attemptCount: Int, outputArtifact: String?, reviewPassNumber: Int) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
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
  fun seedMalformedCurrentCheckpointIdentityStore() {
    seedCheckpointIdentityStore(
      mapOf(
        "contract_version" to
          FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
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
        mode = PROSE,
      ),
      PROSE,
    )
  }
  fun seedResolvedBranch(branch: String, baseBranch: String?, created: Boolean) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordResolvedBranch(
      WORKFLOW_ID,
      FeatureTaskRuntimeResolvedBranch(
        branch = branch,
        baseBranch = baseBranch,
        created = created,
        reviewBaseSha = "0".repeat(40),
      ),
    )
  }
  fun seedBlockedPhase(
    phaseId: String,
    attemptCount: Int,
    agentId: String,
    blockedReason: String,
    failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  ) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
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

  fun seedReentryPhase(seed: SeedReentryPhaseSeed) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = seed.phaseId,
        status = seed.status,
        attemptCount = seed.attemptCount,
        resolvedAgentId = seed.agentId,
        finished = seed.status == "completed",
        outputArtifact = seed.outputArtifact,
        loopId = seed.loopId,
        edgeIteration = seed.edgeIteration,
      ),
    )
  }

  fun seedLoopEdge(phaseId: String, loopId: String, edgeIteration: Int) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.appendLedgerEntry(
      FeatureTaskRuntimePhaseLedgerRequest(
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
  fun seedBranchSetupBlockedPhase(phaseId: String, blockedReason: String) {
    recorder.ensureWorkflowOpen(WORKFLOW_ID, SESSION_ID)
    recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
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
  fun request(transitionsOverride: FeatureTaskRuntimeTransitionDeclaration): FeatureTaskRuntimeRunRequest =
    runRequest.copy(transitionsOverride = transitionsOverride)
}
internal const val BRANCH_SETUP_AGENT_ID = "branch-setup"
internal fun remediationReviewLauncher(git: RecordingWorkflowGitOperations): RuntimeRecordingLauncher =
  RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    if (phaseId == "implement_fix" && git.goalReviewTrackedDelta.isEmpty()) {
      git.goalReviewTrackedDelta = "remediation-progress\n"
    }
    facts(validJsonOutput(phaseId))
  }

internal const val COMMITTED_HEAD_SHA = "ffffffffffffffffffffffffffffffffffffffff"

internal fun committedRepoBranchSetup(): BranchSetupTestConfig = BranchSetupTestConfig(
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
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator =
    NoopFeatureTaskRuntimePlanningProjectionValidator,
  val buildReceiptValidator: FeatureTaskRuntimeBuildReceiptValidator =
    NoopFeatureTaskRuntimeBuildReceiptValidator,
  val codeReviewMode: CodeReviewExecutionMode = CodeReviewExecutionMode.DEFAULT,
  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    FeatureTaskRuntimeSharedEvidenceResolverPort.NONE,
  val diffResolver: DiffResolverPort = object : DiffResolverPort {
    override fun runProcess(args: List<String>, workDir: Path): String? = null
  },
  val validationGateRunner: ValidationGateRunner? = null,
  val validationGatePlatformManifests: List<PlatformManifest> = emptyList(),
  val reviewDriver: FeatureTaskRuntimeReviewDriver =
    FeatureTaskRuntimeReviewDriver.EMPTY,
  val launcher: RuntimeRecordingLauncher? = null,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment? = null,
  val validator: FeatureTaskRuntimePhaseOutputValidator? = null,
  val diagnostics: RuntimeDiagnostics? = null,
)

private fun runtimeSpecSourceResolver(): SpecSourceResolver =
  SpecSourceResolver(TestDecompositionManifestFileStore, testDecompositionManifestValidator)

private data class RuntimePhaseGatesDeps(
  val branchSetupRunner: FeatureTaskRuntimeBranchSetupRunner,
  val planningStopper: FeatureTaskRuntimePlanningStopper,
  val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  val gitOperations: WorkflowGitOperations = NoopWorkflowGitOperations,
  val specGate: FeatureTaskRuntimeSpecGate = testSpecGate(),
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator =
    NoopFeatureTaskRuntimePlanningProjectionValidator,
  val buildReceiptValidator: FeatureTaskRuntimeBuildReceiptValidator =
    NoopFeatureTaskRuntimeBuildReceiptValidator,
  val sharedEvidenceResolver: FeatureTaskRuntimeSharedEvidenceResolverPort =
    FeatureTaskRuntimeSharedEvidenceResolverPort.NONE,
  val diffResolver: DiffResolverPort = object : DiffResolverPort {
    override fun runProcess(args: List<String>, workDir: Path): String? = null
  },
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val validationGateRunnerOverride: ValidationGateRunner? = null,
  val validationGatePlatformManifests: List<PlatformManifest> = emptyList(),
  val reviewDriver: FeatureTaskRuntimeReviewDriver = FeatureTaskRuntimeReviewDriver.EMPTY,
)

private fun runtimePhaseGates(deps: RuntimePhaseGatesDeps): FeatureTaskRuntimePhaseGates {
  val validationGateResolver =
    ValidationGateResolver { deps.validationGatePlatformManifests }
  val validationGateRunner = deps.validationGateRunnerOverride
    ?: object : ValidationGateRunner {
      override fun run(request: ValidationGateRunRequest) = ValidationGateRunResult(
        exitCode = 0,
        durationMs = 1,
        outcome = PASSED,
        cacheMode = request.cacheMode,
        executedWorkUnits = 1,
        findings = emptyList(),
      )
    }
  return FeatureTaskRuntimePhaseGates(
    FeatureTaskRuntimePhaseGateDependencies(
      branchSetupRunner = deps.branchSetupRunner,
      planningStopper = deps.planningStopper,
      lifecycleTelemetry = deps.lifecycleTelemetry,
      gitOperations = deps.gitOperations,
      specGate = deps.specGate,
      planningProjectionValidator = deps.planningProjectionValidator,
      buildReceiptValidator = deps.buildReceiptValidator,
      validationGateResolver = validationGateResolver,
      validationGateRunner = validationGateRunner,
      validationGateCoordinator = FeatureTaskRuntimeValidationGateCoordinator(
        validationGateResolver,
        validationGateRunner,
        FeatureTaskRuntimeValidationGateProgressStore(deps.recorder),
        defaultRepoLocalConfigPort(),
      ),
      buildGateCoordinator = FeatureTaskRuntimeBuildGateCoordinator(
        validationGateResolver,
        validationGateRunner,
        FeatureTaskRuntimeBuildGateProgressStore(deps.recorder),
        defaultRepoLocalConfigPort(),
      ),
      sharedEvidenceResolver = deps.sharedEvidenceResolver,
      diffResolver = deps.diffResolver,
      reviewDriver = deps.reviewDriver,
      specIntentProjectionResolver = SpecIntentProjectionResolver(
        TestDecompositionManifestFileStore,
        testDecompositionManifestValidator,
        SpecIntentProjectionExtractor(
          ReviewContextEnvelopeValidator { _, _ -> },
          TestDecompositionManifestFileStore,
        ),
      ),
      findingVerificationBoundaryMemory = FeatureTaskRuntimeFindingVerificationBoundaryMemory(
        FileSystemGoalPlanningContextDiscovery(),
        FileSystemGoalPlanningBoundaryBodyResolver(),
      ),
    ),
  )
}

private fun defaultRepoLocalConfigPort(): RepoLocalConfigPort = object : RepoLocalConfigPort {
  override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
    ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
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

internal fun smallRuntimeConfig(): RuntimeHarnessConfig = RuntimeHarnessConfig(
  branchSetup = BranchSetupTestConfig(featureSize = FeatureTaskRuntimeFeatureSize.SMALL),
)

internal fun conventionRuntimeConfig(git: RecordingWorkflowGitOperations): RuntimeHarnessConfig =
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
  eventSink = sink,
)
internal data class RunnerHarnessSupervision(
  val crashSupervisor: FeatureTaskRuntimeWorkerSupervisor = HarnessDeadProcessSupervisor,
  val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
)

internal data class RunnerHarnessCore(
  val launcher: RuntimeRecordingLauncher = defaultPhaseAwareLauncher(),
  val validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment = FeatureTaskRuntimeAgentAssignment(),
)

private fun resolvedHarnessSupervision(
  runtimeConfig: RuntimeHarnessConfig,
  supervision: RunnerHarnessSupervision,
): RunnerHarnessSupervision = runtimeConfig.diagnostics?.let { supervision.copy(diagnostics = it) } ?: supervision

internal fun runnerHarness(
  runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
  core: RunnerHarnessCore = RunnerHarnessCore(),
  repository: InMemoryRuntimeWorkflowRepository = InMemoryRuntimeWorkflowRepository(),
  supervision: RunnerHarnessSupervision = RunnerHarnessSupervision(),
): RunnerHarness {
  val launcher = runtimeConfig.launcher ?: core.launcher
  val validator = runtimeConfig.validator ?: core.validator
  val agentAssignment = runtimeConfig.agentAssignment ?: core.agentAssignment
  val resolvedSupervision = resolvedHarnessSupervision(runtimeConfig, supervision)
  harnessPendingVerifyFindingIds = emptyList()
  seedHarnessSpecIntentProjection(runtimeConfig.repoRoot, runtimeConfig.branchSetup.specReference)
  val specScratchStore = RecordingSpecScratchStore()
  val specStatusWriter = RecordingSpecStatusWriter()
  val database = RuntimeFakeDatabaseSessionFactory(repository)
  val recorder = featureTaskRuntimePhaseRecorder(
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
    HarnessRunnerDeps(
      launcher = launcher,
      recorder = recorder,
      goalContinuationRecorder = goalContinuationRecorder,
      runInvariantsStore = runInvariantsStore,
      validator = validator,
      runtimeConfig = runtimeConfig,
      database = database,
      crashSupervisor = resolvedSupervision.crashSupervisor,
      diagnostics = resolvedSupervision.diagnostics,
      specScratchStore = specScratchStore,
      specStatusWriter = specStatusWriter,
      decomposeTerminalRecorder = decomposeTerminalRecorder,
    ),
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
private data class HarnessRunnerDeps(
  val launcher: RuntimeRecordingLauncher,
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val goalContinuationRecorder: FeatureTaskRuntimeGoalContinuationRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  val validator: FeatureTaskRuntimePhaseOutputValidator,
  val runtimeConfig: RuntimeHarnessConfig,
  val database: RuntimeFakeDatabaseSessionFactory,
  val crashSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  val diagnostics: RuntimeDiagnostics,
  val specScratchStore: RecordingSpecScratchStore,
  val specStatusWriter: RecordingSpecStatusWriter,
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
)

private fun harnessRunner(deps: HarnessRunnerDeps): FeatureTaskRuntimeRunner {
  val branchSetupRunner = FeatureTaskRuntimeBranchSetupRunner(
    deps.recorder,
    deps.runtimeConfig.branchSetup.gitOperations,
  )
  val decompositionPlanner =
    if (deps.runtimeConfig.useRealDecompositionPlanner) {
      testDecompositionPlanner()
    } else {
      noOpDecompositionPlanner()
    }
  val planningStopper = FeatureTaskRuntimePlanningStopper(
    deps.validator,
    decompositionPlanner,
    deps.decomposeTerminalRecorder,
    deps.diagnostics,
  )
  return FeatureTaskRuntimeRunner(
    FeatureTaskRuntimeRunnerDependencies(
      subtaskLauncher = deps.launcher,
      recorder = deps.recorder,
      goalContinuationRecorder = deps.goalContinuationRecorder,
      runInvariantsStore = deps.runInvariantsStore,
      outputValidator = deps.validator,
      phaseGates = runtimePhaseGates(
        RuntimePhaseGatesDeps(
          branchSetupRunner = branchSetupRunner,
          planningStopper = planningStopper,
          lifecycleTelemetry = disabledRuntimeLifecycleTelemetry(deps.database),
          gitOperations = deps.runtimeConfig.branchSetup.gitOperations,
          specGate = testSpecGate(deps.specScratchStore, deps.specStatusWriter),
          planningProjectionValidator = deps.runtimeConfig.planningProjectionValidator,
          buildReceiptValidator = deps.runtimeConfig.buildReceiptValidator,
          sharedEvidenceResolver = deps.runtimeConfig.sharedEvidenceResolver,
          diffResolver = deps.runtimeConfig.diffResolver,
          recorder = deps.recorder,
          validationGateRunnerOverride = deps.runtimeConfig.validationGateRunner,
          validationGatePlatformManifests = deps.runtimeConfig.validationGatePlatformManifests,
          reviewDriver = harnessReviewDriverSyncingPendingVerifyFindings(deps.runtimeConfig.reviewDriver),
        ),
      ),
      crashReconciler = FeatureTaskRuntimeCrashReconciler(deps.database, deps.crashSupervisor),
      phaseSettlementService = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository()),
      diagnostics = deps.diagnostics,
    ),
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

private fun telemetryHarnessRequest(runtimeConfig: RuntimeHarnessConfig): FeatureTaskRuntimeRunRequest =
  FeatureTaskRuntimeRunRequest(
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

internal fun telemetryRunnerHarness(runtimeConfig: RuntimeHarnessConfig): TelemetryRunnerHarness =
  telemetryRunnerHarness(
    launcher = runtimeConfig.launcher
      ?: RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) },
    validator = runtimeConfig.validator ?: AlwaysValidValidator,
    runtimeConfig = runtimeConfig,
  )

internal fun telemetryRunnerHarness(
  launcher: RuntimeRecordingLauncher = RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) },
  validator: FeatureTaskRuntimePhaseOutputValidator = AlwaysValidValidator,
  runtimeConfig: RuntimeHarnessConfig = RuntimeHarnessConfig(),
): TelemetryRunnerHarness {
  val effectiveLauncher = runtimeConfig.launcher ?: launcher
  val effectiveValidator = runtimeConfig.validator ?: validator
  seedHarnessSpecIntentProjection(runtimeConfig.repoRoot, runtimeConfig.branchSetup.specReference)
  val repository = InMemoryRuntimeWorkflowRepository()
  val lifecycle = RecordingLifecycleTelemetryRepository()
  val database = RuntimeFakeDatabaseSessionFactory(repository, lifecycle)
  val recorder = featureTaskRuntimePhaseRecorder(
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
    FeatureTaskRuntimeRunnerDependencies(
      subtaskLauncher = effectiveLauncher,
      recorder = recorder,
      goalContinuationRecorder = goalContinuationRecorder,
      runInvariantsStore = runInvariantsStore,
      outputValidator = effectiveValidator,
      phaseGates = runtimePhaseGates(
        RuntimePhaseGatesDeps(
          branchSetupRunner = branchSetupRunner,
          planningStopper = planningStopper,
          lifecycleTelemetry = FeatureTaskRuntimeLifecycleTelemetry(
            LifecycleTelemetryService(database, EnabledRuntimeTelemetrySettingsProvider),
          ),
          gitOperations = runtimeConfig.branchSetup.gitOperations,
          sharedEvidenceResolver = runtimeConfig.sharedEvidenceResolver,
          diffResolver = runtimeConfig.diffResolver,
          recorder = recorder,
          validationGateRunnerOverride = runtimeConfig.validationGateRunner,
          validationGatePlatformManifests = runtimeConfig.validationGatePlatformManifests,
          reviewDriver = harnessReviewDriverSyncingPendingVerifyFindings(runtimeConfig.reviewDriver),
        ),
      ),
      crashReconciler = FeatureTaskRuntimeCrashReconciler(database, NoopFeatureTaskRuntimeWorkerSupervisor),
      phaseSettlementService = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository()),
    ),
  )
  val request = telemetryHarnessRequest(runtimeConfig)
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
internal fun defaultPhaseAwareLauncher(): RuntimeRecordingLauncher = RuntimeRecordingLauncher { request ->
  facts(defaultPhaseOutput(request))
}
internal fun defaultPhaseOutput(request: GoalRunnerSubtaskLaunchRequest): String {
  val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
  return when {
    FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId) -> validJsonOutput(phaseId)
    phaseId == "preplan" || phaseId == "plan" -> validJsonOutput(phaseId)
    phaseId == "review" -> VALID_REVIEW_OUTPUT
    phaseId == "audit" -> VALID_AUDIT_OUTPUT
    phaseId == "verify_findings" -> verifyFindingsOutput()
    else -> validJsonOutput(phaseId)
  }
}
internal const val PLAN_FIX_CAP = 2

internal val PLAN_FIX_CYCLE = FeatureTaskRuntimeTransitionDeclaration(
  forwardPhaseIds = listOf("preplan", "plan"),
  backwardEdges = listOf(
    FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "plan",
      triggeringVerdict = FeatureTaskRuntimeVerdict("needs_fix"),
      destinationPhaseId = "preplan",
      loopId = "plan-fix",
      perEdgeCap = PLAN_FIX_CAP,
    ),
  ),
)
internal const val IMPLEMENT_FIX_CAP = 2

internal val IMPLEMENT_FIX_CYCLE = FeatureTaskRuntimeTransitionDeclaration(
  forwardPhaseIds = listOf("preplan", "plan", "implement", "audit", "review"),
  backwardEdges = listOf(
    FeatureTaskRuntimeBackwardEdge(
      fromPhaseId = "review",
      triggeringVerdict = FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
      destinationPhaseId = "implement",
      loopId = "implement-fix",
      perEdgeCap = IMPLEMENT_FIX_CAP,
    ),
  ),
)
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
internal const val REVIEW_BLOCKER_MESSAGE = "Foo.kt leaks a connection in the error path"
internal fun reviewFindingsOutput(
  changesRequested: Boolean,
  dispositionedBlockerIds: List<String> = emptyList(),
): String {
  val findings = if (changesRequested) {
    """{"severity": "blocker", "finding_id": "$REVIEW_FIX_BLOCKER_FINDING_ID", "message": "$REVIEW_BLOCKER_MESSAGE"}"""
  } else {
    ""
  }
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
internal fun reviewFixDriver(convergeOnReview: Int): FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    val findings = if (reviewPasses < convergeOnReview) {
      listOf(
        ParallelReviewMergedFinding(
          fNumber = REVIEW_FIX_BLOCKER_FINDING_ID,
          agentIds = listOf(request.agent1Id),
          severity = BLOCKER,
          confidence = "High",
          location = "Foo.kt:1",
          description = REVIEW_BLOCKER_MESSAGE,
        ),
      )
    } else {
      emptyList()
    }
    harnessPendingVerifyFindingIds = findings.map { it.fNumber }
    FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
      mergeResult = ParallelReviewMergeResult(
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

internal fun crashingRemediationReviewDriver(): FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    when (reviewPasses) {
      2 ->
        FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
          lane1 = ParallelReviewLaneStatus(
            agentId = request.agent1Id,
            success = false,
            failureReason = "spawn failed",
          ),
        )
      else -> {
        val findings = if (reviewPasses == 1) {
          listOf(
            ParallelReviewMergedFinding(
              fNumber = "F-001",
              agentIds = listOf(request.agent1Id),
              severity = BLOCKER,
              confidence = "High",
              location = "Foo.kt:1",
              description = REVIEW_BLOCKER_MESSAGE,
            ),
          )
        } else {
          emptyList()
        }
        FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
          mergeResult = ParallelReviewMergeResult(
            findings = findings,
            formattedOutput = if (findings.isEmpty()) "NO_FINDINGS" else "findings",
          ),
        )
      }
    }
  }
}

internal fun throwingBudgetReviewDriver(): FeatureTaskRuntimeReviewDriver = FeatureTaskRuntimeReviewDriver {
  throw ReviewContextBudgetExceededException(
    ReviewContextBudgetExceeded(
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

internal fun failingReviewDriver(failOnPass: Int, failureReason: String): FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    if (reviewPasses == failOnPass) {
      FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
        lane1 = ParallelReviewLaneStatus(
          agentId = request.agent1Id,
          success = false,
          failureReason = failureReason,
        ),
      )
    } else {
      FeatureTaskRuntimeReviewDriver.EMPTY.run(request)
    }
  }
}

internal fun crashingReviewFixDriver(
  convergeOnReview: Int,
  crashOnPass: Int,
  shouldCrash: () -> Boolean,
): FeatureTaskRuntimeReviewDriver {
  var reviewPasses = 0
  return FeatureTaskRuntimeReviewDriver { request ->
    reviewPasses += 1
    if (shouldCrash() && reviewPasses == crashOnPass) {
      FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
        lane1 = ParallelReviewLaneStatus(
          agentId = request.agent1Id,
          success = false,
          failureReason = "spawn failed",
        ),
      )
    } else {
      val findings = if (reviewPasses < convergeOnReview) {
        listOf(
          ParallelReviewMergedFinding(
            fNumber = "F-001",
            agentIds = listOf(request.agent1Id),
            severity = BLOCKER,
            confidence = "High",
            location = "Foo.kt:1",
            description = REVIEW_BLOCKER_MESSAGE,
          ),
        )
      } else {
        emptyList()
      }
      FeatureTaskRuntimeReviewDriver.EMPTY.run(request).copy(
        mergeResult = ParallelReviewMergeResult(
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
internal val COMMIT_PUSH_NO_SHA_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "commit_push",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": {"commit_push_result": {"status": "committed"}}
  }
""".trimIndent()

internal val COMMIT_PUSH_BLOCKED_OUTPUT: String = """
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

internal val VALIDATE_BLOCKED_OUTPUT: String = """
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
internal fun goalContinuationLauncher(commitPushOutput: String): RuntimeRecordingLauncher =
  RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    facts(if (phaseId == "commit_push") commitPushOutput else validJsonOutput(phaseId))
  }
internal fun goalContinuationHarness(
  repoRoot: Path,
  git: RecordingWorkflowGitOperations,
  launcher: RuntimeRecordingLauncher,
  reviewDriver: FeatureTaskRuntimeReviewDriver =
    FeatureTaskRuntimeReviewDriver.EMPTY,
): RunnerHarness = runnerHarness(
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
  core = RunnerHarnessCore(launcher = launcher, agentAssignment = phasePerAgentAssignment()),
)

internal val DECOMPOSE_PLAN_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "decomposition_package": {
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
  }
""".trimIndent()
internal val MALFORMED_DECOMPOSE_PLAN_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "decomposition_package": {
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
  }
""".trimIndent()
internal val WRITER_INVALID_DECOMPOSE_PLAN_OUTPUT: String = """
  {
    "contract_version": "0.3",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan needs ordered subtasks.",
    "produced_outputs": {
      "decomposition_package": {
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
  }
""".trimIndent()
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
internal class ThrowingValidator(private val failPhases: Set<String>) : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    if (sourceLabel in failPhases) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "rejected by fake validator")
    }
  }
}

internal object AlwaysValidValidator : FeatureTaskRuntimePhaseOutputValidator {
  override fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) = Unit
}

internal object RepairingImplementOutputValidator : FeatureTaskRuntimePhaseOutputValidator {
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
    val envelope = JsonSupport.parseObjectOrNull(candidate)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      ?: throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "test output is not an object")
    if (envelope["phase_id"] != sourceLabel) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(sourceLabel, "phase_id does not match")
    }
    return envelope
  }
}
internal class RecordingWorkflowGitOperations(
  var currentBranchValue: String = "feat/existing-runtime-branch",
  var currentBranchResult: WorkflowGitOperationResult? = null,
  var checkoutResult: WorkflowGitOperationResult? = null,
  var landedBranchAfterCheckout: String? = null,
  var existingBranches: Set<String>? = null,
  var branchExistsResult: WorkflowGitOperationResult? = null,
) : WorkflowGitOperations,
  CheckpointHistoryGitOperationsProvider,
  GoalSubtaskReviewGitOperationsProvider,
  RepositoryFingerprintGitOperationsProvider,
  RepositoryOwnedPathsGitOperationsProvider,
  RuntimePhaseFileManifestGitOperationsProvider,
  ScopedStagingGitOperationsProvider {
  var headCommitShaValue: String = ""
  var headCommitShaResult: WorkflowGitOperationResult? = null
  val runtimePhaseHeadCommitSequence = ArrayDeque<String>()
  var changedPathsBetweenCommitsValue: String = ""
  var worktreeStatusValue: String = " M src/Foo.kt"
  var worktreeStatusResult: WorkflowGitOperationResult? = null
  val worktreeStatusSequence = ArrayDeque<String>()
  var ownedPathsValue: List<String> = emptyList()
  var ownedPathsResult: WorkflowGitOperationResult? = null
  val repositoryFingerprintSequence = ArrayDeque<String>()
  var repositoryFingerprintValue: String? = null
  var repositoryFingerprintCalls: Int = 0
  val createCommitMessages = mutableListOf<String>()
  var createCommitResult: WorkflowGitOperationResult? = null
  var localBranchHasUnpushedCommitsValue: Boolean = true
  var headCommitMessageValue: String = ""
  val amendCommitMessages = mutableListOf<String>()
  var amendHeadCommitResult: WorkflowGitOperationResult? = null
  val checkpointRefs = mutableMapOf<String, String>()
  val updateCheckpointRefCalls = mutableListOf<Pair<String, String>>()
  var updateCheckpointRefResult: WorkflowGitOperationResult? = null
  var resolveCheckpointRefResult: WorkflowGitOperationResult? = null
  var onResolveCheckpointRef: ((String) -> WorkflowGitOperationResult?)? = null
  var onResolveCommit: ((String) -> WorkflowGitOperationResult?)? = null
  var invalidShaOnRemediationCommit: Boolean = false
  val resetSoftToCommitCalls = mutableListOf<String>()
  var resetSoftToCommitResult: WorkflowGitOperationResult? = null
  val nonAncestorPairs = mutableSetOf<Pair<String, String>>()
  val stagePathsCalls = mutableListOf<String>()
  var stagePathsResult: WorkflowGitOperationResult? = null
  var indexSnapshotValue: String = ""
  var captureIndexStateResult: WorkflowGitOperationResult? = null
  val restoreIndexStateCalls = mutableListOf<String>()
  var restoreIndexStateResult: WorkflowGitOperationResult? = null
  val contentIdentities = mutableMapOf<String, String>()
  var onStagedPathsRead: (() -> Unit)? = null
  var stagedPathsValue: List<String> = emptyList()
  var stagedPathsResult: WorkflowGitOperationResult? = null
  val goalReviewBuildInputs = mutableListOf<GoalSubtaskReviewBaseline>()
  val goalReviewBuildResults = ArrayDeque<GoalSubtaskReviewInputResult>()
  var goalReviewTrackedDelta: String = ""
  var goalReviewRecoveredBaseline: GoalSubtaskReviewBaseline? = null
  var goalReviewRecoverCalls: Int = 0
  val goalReviewRecoverRequests =
    mutableListOf<GoalSubtaskReviewBaselineRecoveryRequest>()

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
  override fun createCommit(repoRoot: Path, message: String): WorkflowGitOperationResult {
    createCommitMessages += message
    if (invalidShaOnRemediationCommit && message.contains("remediation checkpoint")) {
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
        request: GoalSubtaskReviewBaselineRecoveryRequest,
        expectedBranch: String,
      ): GoalSubtaskReviewBaselineResult {
        goalReviewRecoverCalls++
        goalReviewRecoverRequests += request
        return goalReviewRecoveredBaseline?.let { GoalSubtaskReviewBaselineResult(status = "ok", baseline = it) }
          ?: GoalSubtaskReviewBaselineResult(status = "error", error = "no recovered baseline configured")
      }
    }
}
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
  FeatureTaskRuntimePhaseStateRequest(
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
  method.returnType == TYPE -> false
  method.returnType == Integer.TYPE -> 0
  method.returnType == LongTYPE -> 0L
  method.returnType == DoubleTYPE -> 0.0
  else -> null
}

private fun recordHarnessFindingVerdicts(verdicts: MutableList<ReviewFindingVerdict>, args: Array<out Any>?) {
  @Suppress("UNCHECKED_CAST")
  val incoming = args?.getOrNull(1) as? List<ReviewFindingVerdict> ?: return
  incoming.forEach { verdict ->
    verdicts.removeAll { it.findingRef == verdict.findingRef && it.stage == verdict.stage }
    verdicts += verdict
  }
}

private fun harnessReviewRepository(): ReviewRepository {
  val verdicts = mutableListOf<ReviewFindingVerdict>()
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
  val ledgerRows = mutableListOf<UnaddressedFinding>()
  val outcomeRows = mutableListOf<ReviewFindingOutcomeRecord>()
  var producerOutputReadError: RejectedOutputDiagnosticError? = null
  private val diagnosticRecords =
    linkedMapOf<String, RejectedOutputDiagnosticRecord>()
  private val producerEvidence =
    linkedMapOf<ProducerEvidenceKey, ProducerOutputEvidence>()
  private val auditGenerationRows = repository.auditGenerationRows
  private val reviewsPort: ReviewRepository = harnessReviewRepository()
  private val learningsPort: LearningRepository = noopPort(LearningRepository::class.java)
  private val telemetryReconciliationPort: TelemetryReconciliationRepository =
    noopPort(TelemetryReconciliationRepository::class.java)
  private val telemetryOutboxPort: TelemetryOutboxRepository = noopPort(TelemetryOutboxRepository::class.java)

  fun auditGenerations(workflowId: String): List<FeatureTaskRuntimeAuditGenerationRow> =
    auditGenerationRows.filter { it.workflowId == workflowId }.sortedBy { it.generationOrdinal }

  fun rejectedDiagnostics(): List<RejectedOutputDiagnosticRecord> = diagnosticRecords.values.toList()

  fun retainedProducerEvidence(): List<ProducerOutputEvidence> = producerEvidence.values.toList()

  fun retainProducerEvidence(evidence: ProducerOutputEvidence) {
    unitOfWork().rejectedOutputDiagnostics!!.retainProducerOutput(evidence)
  }
  fun producerEvidenceAt(key: ProducerEvidenceKey): ProducerOutputEvidence? = producerEvidence[key]

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
    override val featureTaskRuntimeAuditGenerations =
      object : FeatureTaskRuntimeAuditGenerationRepository {
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
      RejectedOutputDiagnosticPermissions { }
    override val rejectedOutputDiagnostics = object : RejectedOutputDiagnosticRepository {
      override fun insert(record: RejectedOutputDiagnosticRecord): RejectedOutputDiagnosticRecord =
        diagnosticRecords.getOrPut(record.metadata.identity) { record }

      override fun select(selector: RejectedOutputDiagnosticSelector): List<RejectedOutputDiagnostic> =
        diagnosticRecords.values
          .map { it.metadata }
          .filter {
            it.workflowId == selector.workflowId &&
              (selector.phaseId == null || it.phaseId == selector.phaseId) &&
              (selector.attempt == null || it.attempt == selector.attempt)
          }

      override fun read(identity: String): RejectedOutputDiagnosticRecord = diagnosticRecords[identity]
        ?: throw Absent(identity)

      override fun markExpired(before: Instant): Int = 0

      override fun delete(selector: RejectedOutputDiagnosticSelector): Int = 0
      override fun retainProducerOutput(evidence: ProducerOutputEvidence) {
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
          throw Conflict(
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
      ): ProducerOutputEvidence? {
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
    override val unaddressedFindings = object : UnaddressedFindingsRepository {
      override fun replaceLedgerForPass(
        workflowId: String,
        reviewPassNumber: Int,
        findings: List<UnaddressedFinding>,
      ) {
        ledgerRows.removeAll { it.workflowId == workflowId && it.reviewPassNumber <= reviewPassNumber }
        ledgerRows.addAll(findings)
      }

      override fun clearWorkflowLedger(workflowId: String) {
        ledgerRows.removeAll { it.workflowId == workflowId }
      }

      override fun fetchLedger(issueKey: String): List<UnaddressedFinding> =
        ledgerRows.filter { it.issueKey == issueKey }

      override fun fetchWorkflowLedger(workflowId: String): List<UnaddressedFinding> =
        ledgerRows.filter { it.workflowId == workflowId }

      override fun workflowIdsForIssue(issueKey: String): List<String> =
        ledgerRows.filter { it.issueKey == issueKey }.map { it.workflowId }.distinct().sorted()

      override fun recordOutcomes(outcomes: List<ReviewFindingOutcomeRecord>) {
        outcomeRows.removeAll { existing ->
          outcomes.any {
            it.workflowId == existing.workflowId &&
              it.reviewPassNumber == existing.reviewPassNumber &&
              it.findingOrdinal == existing.findingOrdinal
          }
        }
        outcomeRows.addAll(outcomes)
      }

      override fun fetchOutcomes(workflowId: String): List<ReviewFindingOutcomeRecord> =
        outcomeRows.filter { it.workflowId == workflowId }

      override fun issueExists(issueKey: String): Boolean = knownIssue
    }
    override val workList = EmptyWorkListRepository
    override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
  }
}

internal object EnabledRuntimeTelemetrySettingsProvider : TelemetrySettingsProvider {
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

  val auditGenerationRows = mutableListOf<FeatureTaskRuntimeAuditGenerationRow>()

  fun seedWorkerOwnership(ownership: FeatureTaskRuntimeWorkerOwnership) {
    workerOwnership = ownership
  }

  override fun getFeatureTaskRuntimeWorkerOwnership(workflowId: String) =
    synchronized(this) { workerOwnership?.takeIf { it.workflowId == workflowId } }

  override fun acquireFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
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
      leaseState = TAKEOVER_RESERVED,
    )
    true
  }

  override fun transferFeatureTaskRuntimeWorker(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    expectedOwnerToken: String,
    expectedGeneration: Long,
  ): Boolean = synchronized(this) {
    val current = workerOwnership ?: return false
    if (
      current.ownerToken != expectedOwnerToken || current.generation != expectedGeneration ||
      current.leaseState != TAKEOVER_RESERVED
    ) {
      return false
    }
    workerOwnership = ownership
    true
  }

  override fun heartbeatFeatureTaskRuntimeWorker(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean =
    synchronized(this) {
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
  ): List<FeatureTaskRuntimeCrashReconciliationCandidate> = synchronized(this) {
    val ownership = workerOwnership ?: return@synchronized emptyList()
    val row = taskRuntimeRows[ownership.workflowId] ?: return@synchronized emptyList()
    if (row.workflowStatus != "running" || !leaseExpiredBefore(ownership.expiresAt, nowInstant)) {
      return@synchronized emptyList()
    }
    listOf(
      FeatureTaskRuntimeCrashReconciliationCandidate(
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
    runCatching { Instant.parse(expiresAt).isBefore(Instant.parse(nowInstant)) }
      .getOrDefault(false)

  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  override fun findStandaloneFeatureTaskCandidates(normalizedIssueKey: String, repositoryIdentity: String) =
    emptyList<FeatureTaskWorkflowCandidate>()

  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()
  private val implementRows = linkedMapOf<String, WorkflowStateRecord>()

  fun taskRuntimeArtifacts(workflowId: String): Map<String, Any?> {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    return JsonSupport.parseObjectOrNull(record.artifactsJson)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
      .orEmpty()
  }
  fun corruptRecordsArtifact(workflowId: String, corruptValue: Any?) {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    val artifacts = LinkedHashMap(taskRuntimeArtifacts(workflowId)).apply {
      put(FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY, corruptValue)
    }
    taskRuntimeRows[workflowId] = record.copy(
      artifactsJson = JsonSupport.mapToJsonString(artifacts),
    )
  }

  fun replaceTaskRuntimeArtifacts(workflowId: String, artifacts: Map<String, Any?>) {
    val record = requireNotNull(taskRuntimeRows[workflowId]) { "no runtime row for $workflowId" }
    taskRuntimeRows[workflowId] = record.copy(
      artifactsJson = JsonSupport.mapToJsonString(artifacts),
    )
  }
  var failSaveWhen: ((WorkflowStateRecord) -> Boolean)? = null

  override fun saveFeatureTaskRuntimeWorkflow(row: WorkflowStateRecord) {
    if (failSaveWhen?.invoke(row) == true) {
      error("simulated process kill during the feature-task-runtime save")
    }
    taskRuntimeRows[row.workflowId] = row
  }

  fun bumpUpdatedAt(workflowId: String) {
    val row = taskRuntimeRows[workflowId] ?: return
    val current = Instant.parse(row.updatedAt ?: "2026-01-01T00:00:00Z")
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
internal object HarnessDeadProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("harness-host", "harness-boot", 4321, "harness-birth-4321")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.NotRunning

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ) = NoopFeatureTaskRuntimeHeartbeat

  override fun pause(durationMillis: Long) = Unit
}
