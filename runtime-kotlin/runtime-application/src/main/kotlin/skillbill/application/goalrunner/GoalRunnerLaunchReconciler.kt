package skillbill.application.goalrunner
import me.tatarka.inject.annotations.Inject
import skillbill.application.agentoutput.stderrExcerpt
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.goalrunner.GoalRunnerOutcomeReconciler
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerReconcileGate
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

@Inject
public class GoalRunnerLaunchReconciler(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val activityStampWriter: AgentActivityStampWriter,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun subtaskLaunchRequest(args: SubtaskLaunchRequestArgs): GoalRunnerSubtaskLaunchRequest {
    val issueKey = args.issueKey
    val subtaskId = args.subtaskId
    val request = args.request
    val assignedWorkflowId = args.assignedWorkflowId
    val reviewBaseline = args.reviewBaseline
    val spawnAuthorization = args.spawnAuthorization
    val tickReader = GoalRunnerTickProgressReader(
      manifestStore = manifestStore,
      outcomeStore = outcomeStore,
      issueKey = issueKey,
      subtaskId = subtaskId,
      request = request,
    )
    val progressWatermark = runCatching {
      outcomeStore.ledgerSequenceWatermarks(issueKey, request.dbPathOverride).maxProgressSequence
    }.getOrNull()
    val progressEmitter = GoalRunnerProgressEventEmitter(
      outcomeStore = outcomeStore,
      request = request,
      resolveWorkflowId = { tickReader.progressState()?.subtask?.workflowId?.takeIf(String::isNotBlank) },
      watermarkSeed = progressWatermark,
      clock = clock,
      diagnostics = diagnostics,
    )
    val goalContinuation = goalContinuationContext(issueKey, subtaskId, request, assignedWorkflowId, reviewBaseline)
    val activityStampSink = activityStampWriter.lazySink(
      resolveWorkflowId = { tickReader.progressState()?.subtask?.workflowId },
      parentWorkflowId = goalContinuation?.parentWorkflowId,
      dbOverride = request.dbPathOverride,
    )
    return GoalRunnerSubtaskLaunchRequest(
      invokedAgentId = request.invokedAgentId,
      configuredAgentOverrideId = request.configuredAgentOverrideId,
      skillRunRequest = SkillRunRequest(
        issueKey = issueKey,
        repoRoot = request.repoRoot,
        subtaskId = subtaskId,
        dbPathOverride = request.dbPathOverride,
        timeout = request.timeout,
        progressIdleTimeout = request.progressIdleTimeout,
        progressProbe = progressProbe(tickReader, subtaskId),
        declaredProgressProbe = declaredProgressProbe(tickReader),
        progressEmitter = progressEmitter,
        outputSink = request.outputSink,
        readOnlyPhase = goalContinuation?.lastResumableStep ==
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        goalContinuation = goalContinuation,
        spawnAuthorization = spawnAuthorization,
        activityStampSink = activityStampSink,
      ),
    )
  }

  private fun goalContinuationContext(
    issueKey: String,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    assignedWorkflowId: String?,
    reviewBaseline: GoalSubtaskReviewBaseline?,
  ): SkillRunGoalContinuationContext? {
    val state = manifestStore.loadByIssueKey(issueKey, request.dbPathOverride, request.repoRoot) ?: return null
    val branch = state.manifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
      ?: state.manifest.featureBranch?.takeIf(String::isNotBlank)
    val subtask = state.manifest.subtasks.firstOrNull { it.id == subtaskId }
    val specPath = subtask?.specPath?.takeIf(String::isNotBlank)
    return if (branch != null && subtask != null && specPath != null) {
      val childWorkflowId = if (assignedWorkflowId == null) {
        state.manifest.workflowIdFor(subtaskId)
      } else {
        null
      }
      SkillRunGoalContinuationContext(
        parentIssueKey = issueKey,
        subtaskId = subtaskId,
        goalBranch = branch,
        suppressPr = true,
        specPath = specPath,
        parentWorkflowId = state.parentWorkflowId,
        lastResumableStep = subtask.lastResumableStep?.takeIf(String::isNotBlank),
        childWorkflowId = childWorkflowId,
        assignedWorkflowId = assignedWorkflowId,
        codeReviewMode = request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT,
        validationDepth = ValidationDepth.FULL,
        qualityGateSelection = GoalRunnerQualityGateSelectionResolver.resolve(state.manifest, subtaskId),
        agentAddonSelection = manifestStore.effectiveAgentAddonSelection(state.parentWorkflowId, request),
        reviewBaseline = state.manifest.workflowIdFor(subtaskId)
          ?.let { workflowId -> outcomeStore.goalSubtaskReviewState(workflowId, request.dbPathOverride) }
          ?.let { reviewState ->
            GoalSubtaskReviewBaseline(reviewState.reviewBaseSha, reviewState.baselineUntrackedPaths)
          }
          ?: reviewBaseline,
      )
    } else {
      null
    }
  }

  internal fun reconcileLaunchOutcome(
    attemptedState: GoalRunnerManifestState,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerLaunchReconciliation {
    val refreshed = manifestStore.loadByIssueKey(request.issueKey, request.dbPathOverride, request.repoRoot)
      ?: attemptedState
    val launchFacts = launchOutcome.toGoalRunnerLaunchFacts()
    val reconciled = GoalRunnerOutcomeReconciler.reconcile(
      subtaskId = subtaskId,
      launchFacts = launchFacts,
      storedOutcome = storedOutcome(refreshed, subtaskId, request),
    )
    return launchReconciliation(refreshed, reconciled, launchOutcome, subtaskId, request)
  }

  private fun launchReconciliation(
    refreshed: GoalRunnerManifestState,
    reconciled: GoalRunnerReconciledOutcome,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerLaunchReconciliation {
    val recovery = missingResultPrefixRecovery(refreshed, reconciled, launchOutcome, subtaskId, request)
    val recoveredReconciled = recovery?.storedOutcome?.let { recoveredOutcome ->
      GoalRunnerOutcomeReconciler.reconcile(
        subtaskId = subtaskId,
        launchFacts = launchOutcome.toGoalRunnerLaunchFacts(),
        storedOutcome = recoveredOutcome,
      )
    } ?: reconciled
    return GoalRunnerLaunchReconciliation(
      refreshed = refreshed,
      reconciled = recoveredReconciled,
      launchOutcome = launchOutcome,
      diagnostics = recovery?.diagnostics ?: malformedResultJsonDiagnostics(reconciled, launchOutcome),
    )
  }

  private fun missingResultPrefixRecovery(
    refreshed: GoalRunnerManifestState,
    reconciled: GoalRunnerReconciledOutcome,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerMissingResultPrefixRecovery? = missingPrefixRecoveryCandidate(
    reconciled,
    launchOutcome,
  )?.let { candidate ->
    val workflowId = refreshed.manifest.workflowIdFor(subtaskId)
      ?: candidate.workflowId
    val storedOutcome = workflowId?.let { resolvedWorkflowId ->
      outcomeStore.recoverMissingResultPrefixOutput(
        workflowId = resolvedWorkflowId,
        issueKey = request.issueKey,
        subtaskId = subtaskId,
        output = candidate.output,
        dbPathOverride = request.dbPathOverride,
      )
    }
    GoalRunnerMissingResultPrefixRecovery(
      storedOutcome = storedOutcome,
      diagnostics = missingResultPrefixDiagnostics(storedOutcome?.lastResumableStep ?: candidate.lastResumableStep),
    )
  }

  private fun storedOutcome(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerStoredOutcome? {
    val manifestWorkflowId = state.manifest.subtasks.firstOrNull { it.id == subtaskId }?.workflowId
      ?.takeIf(String::isNotBlank)
    if (manifestWorkflowId != null) {
      return outcomeStore.recoverAndPersistTerminalOutcome(
        workflowId = manifestWorkflowId,
        issueKey = state.manifest.issueKey,
        subtaskId = subtaskId,
        repoRoot = request.repoRoot,
        dbPathOverride = request.dbPathOverride,
      )
    }
    return outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = state.manifest.issueKey,
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = false),
      repoRoot = request.repoRoot,
      dbPathOverride = request.dbPathOverride,
    )[subtaskId]
  }
}

fun AgentRunLaunchOutcome.toGoalRunnerLaunchFacts(): GoalRunnerLaunchFacts = when (this) {
  is AgentRunLaunchFacts -> GoalRunnerLaunchFacts(
    timedOut = timedOut,
    interrupted = interrupted,
    spawnFailed = spawnFailed,
    exitStatus = exitStatus,
    stderrExcerpt = stderrExcerpt(stderr, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS),
    liveness = liveness?.let { snapshot ->
      GoalRunnerLivenessSnapshot(
        phase = snapshot.phase,
        reason = snapshot.reason,
        processState = snapshot.processState,
        workflowId = snapshot.workflowId,
        workflowStep = snapshot.workflowStep,
        lastDurableProgressAt = snapshot.lastDurableProgressAt,
        lastDurableProgressLabel = snapshot.lastDurableProgressLabel,
        lastWorkflowSnapshotAt = snapshot.lastWorkflowSnapshotAt,
        lastFileActivityAt = snapshot.lastFileActivityAt,
        lastFileActivityLabel = snapshot.lastFileActivityLabel,
        lastOutputAt = snapshot.lastOutputAt,
        livenessState = snapshot.livenessState,
        aliveAtKill = snapshot.livenessState == GoalRunnerLivenessState.WORKING ||
          snapshot.livenessState == GoalRunnerLivenessState.PROGRESSING,
      )
    },
  )
  is UnsupportedAgentRunLaunch -> GoalRunnerLaunchFacts(spawnFailed = true)
}
