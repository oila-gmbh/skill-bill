package skillbill.engine.featuretask

import skillbill.application.review.toProjectionPayload
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.FeatureTaskRuntimeProjectionRejection
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.goalrunner.subtaskreview.model.StructuredGoalReviewFinding
import skillbill.goalrunner.subtaskreview.verificationBoundaryFindingPaths
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.repository.toFileLocation
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResultimport skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeRunLoopLaunch {
  internal fun findingPathsForBoundaryMemory(finding: StructuredGoalReviewFinding): List<String> =
    GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(finding)

  internal fun verifyFindingsSpecIntentSection(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return ""
    val checkpoint = runLoop.recorder.loadFindingVerificationCheckpoint(
      run.request.workflowId,
    )
    val boundarySelection = runLoop.recorder.loadFindingVerificationBoundarySelection(
      run.request.workflowId,
    )?.takeIf { it.isNotEmpty() }
    val resolution = runLoop.phaseGates.specIntentProjectionResolver.resolve(
      SpecIntentProjectionResolveRequest(
        repoRoot = run.request.repoRoot,
        explicitSpecPath = Path.of(run.request.runInvariants.specReference),
        branchName = runLoop.session.resolvedBranch ?: "HEAD",
        changedPaths = emptyList(),
        budget = ReviewContextBudgetPolicy.DEFAULT,
      ),
    )
    val boundarySections = runLoop.collaborators.outputVerificationContinued2
      .findingVerificationBoundarySections(runLoop, run)
    return buildString {
      when (resolution) {
        is SpecIntentResolution.Resolved -> {
          appendLine()
          appendLine("## Spec intent projection (verify_findings)")
          appendLine(JsonSupport.mapToJsonString(resolution.projection.toProjectionPayload()))
        }
        is SpecIntentResolution.None -> Unit
      }
      append(runLoop.phaseGates.findingVerificationBoundaryMemory.promptSection(boundarySections))
      if (boundarySelection != null) {
        append(
          runLoop.phaseGates.findingVerificationBoundaryMemory.resolvedBodiesPromptSection(
            repoRoot = run.request.repoRoot,
            sections = boundarySections,
            selectionsByFindingId = boundarySelection,
          ),
        )
      }
      if (!checkpoint.isNullOrEmpty()) {
        appendLine()
        appendLine("## Persisted verify_findings checkpoint")
        appendLine(
          "Reuse these in-flight dispositions verbatim unless repository evidence contradicts them; " +
            "do not mint a second verification pass.",
        )
        appendLine(
          checkpoint.joinToString(prefix = "[", postfix = "]") { disposition ->
            JsonSupport.mapToJsonString(disposition.toArtifactMap())
          },
        )
      }
    }
  }

  internal fun launchAndCapture(
    runLoop: FeatureTaskRuntimeRunLoop,
    attempt: PhaseAttemptContext,
    priorCorrection: PriorAttemptCorrection? = null,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): LaunchResult {
    val run = attempt.run
    val state = attempt.state
    val before = when (val captured = runLoop.collaborators.launchContinued1.captureLaunchBeforeState(runLoop, run)) {
      is LaunchCaptureBeforeResult.Ready -> captured.state
      is LaunchCaptureBeforeResult.Failed ->
        return runLoop.collaborators.launchContinued1.launchCaptureInfraFailure(
          run.phaseId,
          captured.detail,
          childNeverLaunched = true,
        )
    }
    val prepared = when (
      val preparation = prepareLaunchForCapture(runLoop, run, state, attempt.iteration, priorCorrection)
    ) {
      is PreparedLaunchReady -> preparation.value
      is LaunchPreparationRejected -> return preparation.result
      is LaunchMeasurementContextReady,
      is ClosedCriterionRefsReady,
      -> error("Unexpected launch preparation result.")
    }
    val (
      isReviewPhase,
      isVerifyFindingsPhase,
    ) = runLoop.collaborators.launchContinued1.isReadOnlyLaunchPhase(run.phaseId)
    val outcome = runLoop.collaborators.launchContinued1.executeSubtaskLaunch(
      runLoop,
      run,
      prepared,
      isReviewPhase,
      isVerifyFindingsPhase,
    )
    runLoop.collaborators.launchContinued1.recordLaunchTokenUsage(
      run,
      prepared.briefing,
      outcome,
      runLoop.phaseTokenAccumulator,
    )
    val fileManifest = when (
      val captured = runLoop.collaborators.launchContinued1.buildLaunchFileManifest(
        runLoop,
        run,
        before,
      )
    ) {
      is LaunchCaptureAfterResult.Ready -> captured.manifest
      is LaunchCaptureAfterResult.Failed ->
        return runLoop.collaborators.launchContinued1.launchCaptureInfraFailure(
          run.phaseId,
          captured.detail,
          childNeverLaunched = false,
        )
    }
    capturePhaseContentIdentities(runLoop, run.phaseId)
    return runLoop.collaborators.launchContinued3.reconcileLaunch(run.phaseId, outcome, fileManifest)
  }

  /**
   * Records what the phase left on disk the instant it stopped running. Anything that differs from
   * this at checkpoint time was written by someone other than the phase, which is the only way to
   * detect a concurrent unstaged edit to a file this workflow owns.
   */
  fun capturePhaseContentIdentities(runLoop: FeatureTaskRuntimeRunLoop, phaseId: String) {
    val owned = runLoop.gitOperations.repositoryOwnedPaths(runLoop.request.repoRoot)
    if (!owned.ok) return
    val paths = owned.value.orEmpty().split(OWNED_PATH_DELIMITER).map(String::trim).filter(String::isNotBlank)
    val identities = runLoop.gitOperations.pathContentIdentities(runLoop.request.repoRoot, paths)
    if (!identities.ok) return
    runLoop.session.phaseContentIdentities[phaseId] = parseContentIdentities(identities.value.orEmpty())
  }

  fun parseContentIdentities(raw: String): Map<String, String> = raw
    .split(OWNED_PATH_DELIMITER)
    .filter(String::isNotBlank)
    .mapNotNull { record ->
      val identity = record.substringBefore('\t', missingDelimiterValue = "")
      val path = record.substringAfter('\t', missingDelimiterValue = "")
      if (identity.isBlank() || path.isBlank()) null else path to identity
    }
    .toMap()

  internal fun prepareLaunchForCapture(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    priorCorrection: PriorAttemptCorrection?,
  ): LaunchPreparation {
    val measurementContext = when (
      val resolution = runLoop.collaborators.launchContinued2.resolveLaunchMeasurementContext(
        runLoop,
        run,
        state,
      )
    ) {
      is LaunchMeasurementContextReady -> resolution.value
      is LaunchPreparationRejected -> return resolution
      is PreparedLaunchReady,
      is ClosedCriterionRefsReady,
      -> error("Unexpected launch measurement result.")
    }
    val durablyClosedCriterionRefs = when (
      val resolution = runLoop.collaborators.launchContinued2.resolveDurablyClosedCriterionRefs(
        runLoop,
        run,
        state,
        measurementContext,
      )
    ) {
      is ClosedCriterionRefsReady -> resolution.value
      is LaunchPreparationRejected -> return resolution
      is PreparedLaunchReady,
      is LaunchMeasurementContextReady,
      -> error("Unexpected closed-criterion result.")
    }
    return runLoop.collaborators.launchContinued2.prepareDeclaredLaunch(
      runLoop,
      DeclaredLaunchArgs(run, state, iteration, priorCorrection, durablyClosedCriterionRefs, measurementContext),
    )
  }

  internal data class LaunchCaptureBeforeState(
    val beforeManifest: String,
    val beforeCommit: String,
  )

  internal sealed interface LaunchCaptureBeforeResult {
    data class Ready(val state: LaunchCaptureBeforeState) : LaunchCaptureBeforeResult
    data class Failed(val detail: String) : LaunchCaptureBeforeResult
  }

  internal sealed interface LaunchCaptureAfterResult {
    data class Ready(val manifest: FeatureTaskRuntimePhaseFileManifest) : LaunchCaptureAfterResult
    data class Failed(val detail: String) : LaunchCaptureAfterResult
  }

  internal fun captureLaunchBeforeState(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
  ): FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeResult {
    val before = runLoop.gitOperations.worktreeStatus(run.request.repoRoot)
    if (before !is WorkflowGitOperationResult.Ok) {
      return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeResult.Failed("before-file manifest: ${before.error}")
    }
    val beforeCommit = runLoop.gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (beforeCommit !is WorkflowGitOperationResult.Ok) {
      return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeResult.Failed("before commit")
    }
    return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeResult.Ready(
      FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeState(
        beforeManifest = before.value.orEmpty(),
        beforeCommit = beforeCommit.value.orEmpty(),
      ),
    )
  }

  internal fun launchCaptureInfraFailure(phaseId: String, detail: String, childNeverLaunched: Boolean): LaunchResult =
    LaunchResult.infraFailure(
      "Feature-task-runtime phase '$phaseId' could not capture its $detail",
      childNeverLaunched = childNeverLaunched,
    )

  internal fun executeSubtaskLaunch(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    prepared: PreparedLaunch,
    isReviewPhase: Boolean,
    isVerifyFindingsPhase: Boolean,
  ): AgentRunLaunchOutcome {
    val launched = FeatureTaskRuntimeRunLoopOutputPersistence.launchedModelDirective(run)
    return runLoop.subtaskLauncher.launch(
      GoalRunnerSubtaskLaunchRequest(
        invokedAgentId = run.resolvedAgent.invokedAgentId,
        configuredAgentOverrideId = run.resolvedAgent.configuredAgentOverrideId,
        skillRunRequest = SkillRunRequest(
          issueKey = run.request.issueKey,
          repoRoot = run.request.repoRoot,
          timeout = run.request.timeout,
          modelOverride = launched.modelOverride,
          effortOverride = launched.effortOverride,
          compaction = run.compaction,
          promptOverride = prepared.prompt,
          readOnlyPhase = isReviewPhase || isVerifyFindingsPhase,
          progressIdleTimeout = READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES.minutes
            .takeIf { isReviewPhase || isVerifyFindingsPhase },
          activityStampSink = runLoop.activityStampWriter.sink(
            workflowId = run.request.workflowId,
            parentWorkflowId = run.request.goalContinuation?.parentWorkflowId,
          ),
        ),
      ),
    )
  }

  internal fun buildLaunchFileManifest(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    before: FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeState,
  ): FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult {
    val after = runLoop.gitOperations.worktreeStatus(run.request.repoRoot)
    if (after !is WorkflowGitOperationResult.Ok) {
      return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Failed("after-file manifest")
    }
    val afterCommit = runLoop.gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (afterCommit !is WorkflowGitOperationResult.Ok) {
      return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Failed("after commit")
    }
    val committedPaths = runLoop.gitOperations.runtimePhaseChangedPathsBetweenCommits(
      run.request.repoRoot,
      before.beforeCommit,
      afterCommit.value.orEmpty(),
    )
    if (committedPaths !is WorkflowGitOperationResult.Ok) {
      return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Failed("committed file changes")
    }
    return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Ready(
      FeatureTaskRuntimePhaseFileManifest(
        before = FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(before.beforeManifest),
        after = (
          FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(after.value) +
            FeatureTaskRuntimePhaseSafetyPolicy.lineSeparatedPaths(committedPaths.value.orEmpty())
          ).distinct().sorted(),
      ),
    )
  }

  internal fun recordLaunchTokenUsage(
    run: PhaseRun,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    outcome: AgentRunLaunchOutcome,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
  ) {
    if (outcome is AgentRunLaunchFacts && phaseTokenAccumulator != null) {
      val inputTokens = estimateTokens(briefing.briefingText)
      val outputTokens = estimateTokens(outcome.stdout)
      phaseTokenAccumulator[run.phaseId] = Pair(inputTokens, outputTokens)
    }
  }

  fun isReadOnlyLaunchPhase(phaseId: String): Pair<Boolean, Boolean> {
    val isReviewPhase = phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
    val isVerifyFindingsPhase = phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS
    return isReviewPhase to isVerifyFindingsPhase
  }

  internal fun resolveLaunchMeasurementContext(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
  ): LaunchPreparation {
    val producerIteration = run.declaration.projectionDeclarations
      .map { declaration ->
        val phaseId = declaration.producerIteration.phaseId
        state.outputFor(phaseId)?.let { FeatureTaskRuntimeProducerIteration(phaseId, it.iteration) }
          ?: declaration.producerIteration
      }
      .maxByOrNull(FeatureTaskRuntimeProducerIteration::iteration)
      ?: FeatureTaskRuntimeProducerIteration(run.phaseId, 1)
    return try {
      LaunchMeasurementContextReady(
        LaunchRejectionMeasurementContext(
          producerIteration = producerIteration,
          repositoryCheckpoint = FeatureTaskRuntimeRunLoopOutputVerification.resolveRepositoryCheckpoint(
            runLoop,
            run,
          ),
        ),
      )
    } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
      recordLaunchSeamRejection(
        runLoop,
        LaunchSeamRejectionArgs(
          run = run,
          state = state,
          classification = FeatureTaskRuntimeProjectionFailureClassification.BUDGET_OVERFLOW,
          sourceLabel = error.projectionName,
          fallbackProducerIteration = producerIteration,
          repositoryCheckpoint = null,
        ),
      )
      LaunchPreparationRejected(
        LaunchResult.projectionRejected(
          "Feature-task-runtime phase '${run.phaseId}' could not resolve its repository checkpoint: ${error.message}",
        ),
      )
    }
  }

  internal fun resolveDurablyClosedCriterionRefs(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparation = try {
    ClosedCriterionRefsReady(
      if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
        FeatureTaskRuntimeRunLoopPhaseRunner.durablyClosedCriterionRefs()
      } else {
        emptyList()
      },
    )
  } catch (error: InvalidWorkflowStateSchemaError) {
    recordLaunchSeamRejection(
      runLoop,
      LaunchSeamRejectionArgs(
        run = run,
        state = state,
        classification = FeatureTaskRuntimeProjectionFailureClassification.UNSUPPORTED_VERSION,
        sourceLabel = "durable_audit_state",
        fallbackProducerIteration = context.producerIteration,
        repositoryCheckpoint = context.repositoryCheckpoint,
      ),
    )
    LaunchPreparationRejected(
      LaunchResult.projectionRejected(
        "Feature-task-runtime phase '${run.phaseId}' rejected its durable audit-repair state at the launch seam: " +
          error.message,
      ),
    )
  }

  internal fun prepareDeclaredLaunch(runLoop: FeatureTaskRuntimeRunLoop, args: DeclaredLaunchArgs): LaunchPreparation =
    FeatureTaskRuntimeRunLoopLaunch.prepareDeclaredLaunchBody(runLoop, args)

  internal fun recordLaunchSeamRejection(runLoop: FeatureTaskRuntimeRunLoop, args: LaunchSeamRejectionArgs) {
    val run = args.run
    val state = args.state
    val classification = args.classification
    val sourceLabel = args.sourceLabel
    val fallbackProducerIteration = args.fallbackProducerIteration
    val repositoryCheckpoint = args.repositoryCheckpoint
    val attribution = resolveLaunchRejectionAttribution(
      declarations = run.declaration.projectionDeclarations,
      projectionName = sourceLabel,
      currentProducerIteration = { phaseId -> state.outputFor(phaseId)?.iteration },
      fallbackProducerIteration = fallbackProducerIteration,
    )
    runLoop.recorder.recordProjectionRejection(
      FeatureTaskRuntimeProjectionRejection(
        workflowId = run.request.workflowId,
        consumerPhaseId = run.phaseId,
        projectionContractId = attribution.projectionContractId,
        producerIteration = attribution.producerIteration,
        repositoryCheckpointFingerprint = repositoryCheckpoint?.fingerprint,
        failureClassification = classification,
        sourceLabel = sourceLabel,
      ),
    )
  }

  internal fun priorGapMemoryFor(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
  ): FeatureTaskRuntimePriorGapMemory? {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val auditGapFired = state.edgeIterationCount(def.AUDIT_GAP_LOOP_ID) > 0
    val implementReentry = run.phaseId == def.PHASE_IMPLEMENT &&
      (run.reentry?.loopId == def.AUDIT_GAP_LOOP_ID || auditGapFired)
    val auditAfterRemediation = run.phaseId == def.PHASE_AUDIT && auditGapFired
    if (!implementReentry && !auditAfterRemediation) {
      return null
    }
    val round = (
      run.reentry?.takeIf { it.loopId == def.AUDIT_GAP_LOOP_ID }?.edgeIteration
        ?: state.edgeIterationCount(def.AUDIT_GAP_LOOP_ID)
      ).coerceAtLeast(1)
    val auditOutputs = state.outputs()
      .filter { it.phaseId == def.PHASE_AUDIT }
      .sortedBy { it.iteration }
    if (auditOutputs.isEmpty()) return null
    val auditValues = auditOutputs.mapNotNull { output ->
      FeatureTaskRuntimeRunLoopLaunch.outputEnvelopeOf(output)
        ?.let(FeatureTaskRuntimeOutputVerification::auditProseValue)
    }
    if (auditValues.isEmpty()) return null
    val priorAuditValues = if (implementReentry) {
      auditValues.dropLast(1)
    } else {
      auditValues
    }
    val bounded = boundPriorGapNotes(priorAuditValues)
    if (bounded.droppedForListCap > 0 || bounded.droppedForUtf8Budget > 0) {
      runCatching {
        runLoop.diagnostics.warning(
          "seam=FeatureTaskRuntimeRunLoop.priorGapMemoryFor " +
            "value_expected=bounded_prior_gap_memory " +
            "value_used=dropped_whole_values " +
            "cause=dropped_entries=${bounded.droppedForListCap};" +
            "dropped_over_utf8=${bounded.droppedForUtf8Budget}",
        )
      }
    }
    return FeatureTaskRuntimePriorGapMemory(
      round = round,
      priorAuditValues = bounded.values,
    )
  }

  fun outputEnvelopeOf(output: FeatureTaskRuntimePhaseOutput): Map<String, Any?>? =
    output.normalizedOutput?.envelope?.takeIf { it.isNotEmpty() }
      ?: JsonCodec.parseObjectOrNull(output.payload)?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)

  internal fun reconcileLaunch(
    phaseId: String,
    outcome: AgentRunLaunchOutcome,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ): LaunchResult = when (outcome) {
    is UnsupportedAgentRunLaunch -> LaunchResult.infraFailure(
      "Feature-task-runtime phase '$phaseId' could not launch an agent: ${outcome.reason}",
      fileManifest,
      childNeverLaunched = true,
    )
    is AgentRunLaunchFacts -> providerLimitSignal(outcome)
      ?.let { LaunchResult.providerLimited(providerLimitPauseReason(phaseId, it), fileManifest) }
      ?: infraFailureReason(phaseId, outcome)
        ?.let {
          LaunchResult.infraFailure(
            it,
            fileManifest,
            childNeverLaunched = outcome.spawnFailed || !outcome.processStarted,
            childOutput = featureTaskRuntimeChildOutput(outcome),
          )
        }
      ?: LaunchResult.captured(
        LaunchCapturedArgs(
          stdout = outcome.stdout,
          stdoutBytes = outcome.stdoutBytes,
          stdoutTruncated = outcome.stdoutTruncated,
          stdoutByteSize = outcome.stdoutByteSize,
          stdoutSha256 = outcome.stdoutSha256,
          fileManifest = fileManifest,
        ),
      )
  }

  internal fun launchPreparationRejected(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: LaunchPreparationRejectedArgs,
  ): LaunchPreparationRejected {
    FeatureTaskRuntimeRunLoopLaunch.recordLaunchSeamRejection(
      runLoop,
      LaunchSeamRejectionArgs(
        run = args.run,
        state = args.state,
        classification = args.classification,
        sourceLabel = args.sourceLabel,
        fallbackProducerIteration = args.measurement.producerIteration,
        repositoryCheckpoint = args.measurement.repositoryCheckpoint,
      ),
    )
    return LaunchPreparationRejected(LaunchResult.projectionRejected(args.message))
  }

  internal fun prepareDeclaredLaunchBody(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: DeclaredLaunchArgs,
  ): LaunchPreparation {
    val run = args.run
    val state = args.state
    val priorCorrection = args.priorCorrection
    val durablyClosedCriterionRefs = args.durablyClosedCriterionRefs
    val context = args.context
    return try {
      PreparedLaunchReady(
        FeatureTaskRuntimeRunLoopOutputPersistence.prepareLaunch(
          runLoop,
          PrepareLaunchArgs(run, state, priorCorrection, durablyClosedCriterionRefs, context.repositoryCheckpoint),
        ),
      )
    } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
      rejectedHandoffLaunch(runLoop, run, state, error, context)
    } catch (error: InvalidFeatureTaskRuntimePhaseBriefingFramingError) {
      rejectedBriefingLaunch(runLoop, run, state, error, context)
    } catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
      rejectedPlanningProjectionLaunch(runLoop, run, state, error, context)
    } catch (error: InvalidWorkflowStateSchemaError) {
      rejectedDurableBriefingLaunch(runLoop, run, state, error, context)
    }
  }

  private fun rejectedHandoffLaunch(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparationRejected = launchPreparationRejected(
    runLoop,
    LaunchPreparationRejectedArgs(
      run = run,
      state = state,
      classification = error.failureKind.toMeasurementFailureClassification(),
      sourceLabel = error.projectionName,
      measurement = context,
      message = "Feature-task-runtime phase '${run.phaseId}' could not build its declared handoff " +
        "projection: ${error.message}",
    ),
  )

  private fun rejectedBriefingLaunch(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    error: InvalidFeatureTaskRuntimePhaseBriefingFramingError,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparationRejected = launchPreparationRejected(
    runLoop,
    LaunchPreparationRejectedArgs(
      run = run,
      state = state,
      classification = FeatureTaskRuntimeProjectionFailureClassification.BUDGET_OVERFLOW,
      sourceLabel = "phase_briefing",
      measurement = context,
      message = "Feature-task-runtime phase '${run.phaseId}' could not fit its launch briefing under " +
        "the byte ceiling: ${error.message}",
    ),
  )

  private fun rejectedPlanningProjectionLaunch(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparationRejected {
    FeatureTaskRuntimeRunLoopLaunch.recordLaunchSeamRejection(
      runLoop,
      LaunchSeamRejectionArgs(
        run = run,
        state = state,
        classification = FeatureTaskRuntimeProjectionFailureClassification.INVALID_CONTRACT,
        sourceLabel = error.projectionName ?: "planning_projection",
        fallbackProducerIteration = context.producerIteration,
        repositoryCheckpoint = context.repositoryCheckpoint,
      ),
    )
    return LaunchPreparationRejected(
      LaunchResult.recordRejected(
        QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION,
        error.message.orEmpty(),
      ),
    )
  }

  private fun rejectedDurableBriefingLaunch(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    error: InvalidWorkflowStateSchemaError,
    context: LaunchRejectionMeasurementContext,
  ): LaunchPreparationRejected = launchPreparationRejected(
    runLoop,
    LaunchPreparationRejectedArgs(
      run = run,
      state = state,
      classification = FeatureTaskRuntimeProjectionFailureClassification.UNSUPPORTED_VERSION,
      sourceLabel = "durable_briefing",
      measurement = context,
      message = "Feature-task-runtime phase '${run.phaseId}' rejected a durable handoff envelope at " +
        "the launch seam: ${error.message}",
    ),
  )}
