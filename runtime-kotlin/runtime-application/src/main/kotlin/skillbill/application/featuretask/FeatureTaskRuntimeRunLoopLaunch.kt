package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimeProjectionRejection
import skillbill.application.review.toProjectionPayload
import skillbill.application.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.application.subtaskreview.StructuredGoalReviewFinding
import skillbill.application.subtaskreview.verificationBoundaryFindingPaths
import skillbill.contracts.JsonCodec
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.boundPriorGapNotes
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

object FeatureTaskRuntimeRunLoopLaunch {
  internal fun findingPathsForBoundaryMemory(finding: StructuredGoalReviewFinding): List<String> =
    GoalSubtaskReviewSummaryReducer.verificationBoundaryFindingPaths(finding)

  internal fun verifyFindingsSpecIntentSection(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS) return ""
    val checkpoint = runLoop.recorder.loadFindingVerificationCheckpoint(
      run.request.workflowId,
      run.request.dbPathOverride,
    )
    val boundarySelection = runLoop.recorder.loadFindingVerificationBoundarySelection(
      run.request.workflowId,
      run.request.dbPathOverride,
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
    val boundarySections = FeatureTaskRuntimeRunLoopOutputVerification
      .findingVerificationBoundarySections(runLoop, run)
    return buildString {
      when (resolution) {
        is SpecIntentResolution.Resolved -> {
          appendLine()
          appendLine("## Spec intent projection (verify_findings)")
          appendLine(JsonCodec.mapToJsonString(resolution.projection.toProjectionPayload()))
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
            JsonCodec.mapToJsonString(disposition.toArtifactMap())
          },
        )
      }
    }
  }

  internal fun launchAndCapture(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    priorCorrection: PriorAttemptCorrection? = null,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): LaunchResult {
    val before = when (val captured = FeatureTaskRuntimeRunLoopLaunch.captureLaunchBeforeState(runLoop, run)) {
      is LaunchCaptureBeforeResult.Ready -> captured.state
      is LaunchCaptureBeforeResult.Failed ->
        return FeatureTaskRuntimeRunLoopLaunch.launchCaptureInfraFailure(
          run.phaseId,
          captured.detail,
          childNeverLaunched = true,
        )
    }
    val prepared = when (val preparation = prepareLaunchForCapture(runLoop, run, state, priorCorrection)) {
      is PreparedLaunchReady -> preparation.value
      is LaunchPreparationRejected -> return preparation.result
      is LaunchMeasurementContextReady,
      is ClosedCriterionRefsReady,
      -> error("Unexpected launch preparation result.")
    }
    val (
      isReviewPhase,
      isVerifyFindingsPhase,
    ) = FeatureTaskRuntimeRunLoopLaunch.isReadOnlyLaunchPhase(run.phaseId)
    val outcome = FeatureTaskRuntimeRunLoopLaunch.executeSubtaskLaunch(
      runLoop,
      run,
      prepared,
      isReviewPhase,
      isVerifyFindingsPhase,
    )
    FeatureTaskRuntimeRunLoopLaunch.recordLaunchTokenUsage(
      run,
      prepared.briefing,
      outcome,
      runLoop.phaseTokenAccumulator,
    )
    val fileManifest = when (
      val captured = FeatureTaskRuntimeRunLoopLaunch.buildLaunchFileManifest(
        runLoop,
        run,
        before,
      )
    ) {
      is LaunchCaptureAfterResult.Ready -> captured.manifest
      is LaunchCaptureAfterResult.Failed ->
        return FeatureTaskRuntimeRunLoopLaunch.launchCaptureInfraFailure(
          run.phaseId,
          captured.detail,
          childNeverLaunched = false,
        )
    }
    capturePhaseContentIdentities(runLoop, run.phaseId)
    return FeatureTaskRuntimeRunLoopLaunch.reconcileLaunch(run.phaseId, outcome, fileManifest)
  }

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
    priorCorrection: PriorAttemptCorrection?,
  ): LaunchPreparation {
    val measurementContext = when (
      val resolution = FeatureTaskRuntimeRunLoopLaunch.resolveLaunchMeasurementContext(
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
      val resolution = FeatureTaskRuntimeRunLoopLaunch.resolveDurablyClosedCriterionRefs(
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
    return FeatureTaskRuntimeRunLoopLaunch.prepareDeclaredLaunch(
      runLoop,
      DeclaredLaunchArgs(run, state, priorCorrection, durablyClosedCriterionRefs, measurementContext),
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
    if (!before.ok) {
      return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureBeforeResult.Failed("before-file manifest: ${before.error}")
    }
    val beforeCommit = runLoop.gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!beforeCommit.ok) {
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
          dbPathOverride = run.request.dbPathOverride,
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
            dbOverride = run.request.dbPathOverride,
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
    if (!after.ok) return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Failed("after-file manifest")
    val afterCommit = runLoop.gitOperations.runtimePhaseHeadCommit(run.request.repoRoot)
    if (!afterCommit.ok) return FeatureTaskRuntimeRunLoopLaunch.LaunchCaptureAfterResult.Failed("after commit")
    val committedPaths = runLoop.gitOperations.runtimePhaseChangedPathsBetweenCommits(
      run.request.repoRoot,
      before.beforeCommit,
      afterCommit.value.orEmpty(),
    )
    if (!committedPaths.ok) {
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
      run.request.dbPathOverride,
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
  )
}

internal sealed interface AttemptResult {
  data class Settled(val outcome: PhaseOutcome) : AttemptResult

  data class SchemaInvalid(
    val operatorReason: String,
    val retryReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    override val rejectedOutput: String?,
    override val malformedOutput: Boolean,
    override val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?,
  ) : AttemptResult

  data class IncompleteWork(
    val operatorReason: String,
    val continuationReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  ) : AttemptResult

  data class RetryableTerminal(
    val operatorReason: String,
    val retryReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    val failureDisposition: FeatureTaskRuntimeFailureDisposition,
  ) : AttemptResult

  data class BoundaryBodyDelivery(
    val continuationReason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ) : AttemptResult

  data class FindingsOwed(
    val kind: FindingsOwedKind,
    val operatorReason: String,
    val retryReason: String,
    val refs: Set<String>,
    val detail: String?,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  ) : AttemptResult

  val settledOutcome: PhaseOutcome? get() = (this as? Settled)?.outcome
  val schemaInvalidOperatorReason: String? get() = (this as? SchemaInvalid)?.operatorReason
  val schemaInvalidRetryReason: String? get() = (this as? SchemaInvalid)?.retryReason
  val fileManifest: FeatureTaskRuntimePhaseFileManifest?
    get() = when (this) {
      is Settled -> null
      is SchemaInvalid -> fileManifest
      is IncompleteWork -> fileManifest
      is RetryableTerminal -> fileManifest
      is FindingsOwed -> fileManifest
      is BoundaryBodyDelivery -> fileManifest
    }
  val rejectedOutput: String? get() = (this as? SchemaInvalid)?.rejectedOutput
  val malformedOutput: Boolean get() = (this as? SchemaInvalid)?.malformedOutput == true
  val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext?
    get() = (this as? SchemaInvalid)?.correctiveRepairContext

  val retryableOperatorReason: String?
    get() = when (this) {
      is Settled -> null
      is SchemaInvalid -> operatorReason
      is IncompleteWork -> operatorReason
      is RetryableTerminal -> operatorReason
      is FindingsOwed -> operatorReason
      is BoundaryBodyDelivery -> null
    }

  val semanticRetryReason: String?
    get() = when (this) {
      is Settled -> null
      is SchemaInvalid -> retryReason
      is IncompleteWork -> null
      is RetryableTerminal -> null
      is FindingsOwed -> null
      is BoundaryBodyDelivery -> null
    }

  val retryableTerminalRetryReason: String? get() = (this as? RetryableTerminal)?.retryReason

  val retryableTerminalDisposition: FeatureTaskRuntimeFailureDisposition?
    get() = (this as? RetryableTerminal)?.failureDisposition

  val findingsOwedKind: FindingsOwedKind? get() = (this as? FindingsOwed)?.kind

  val findingsOwedRefs: Set<String>? get() = (this as? FindingsOwed)?.refs

  val findingsOwedRetryReason: String? get() = (this as? FindingsOwed)?.retryReason

  val findingsOwedDetail: String? get() = (this as? FindingsOwed)?.detail

  val incompleteWorkContinuationReason: String? get() = (this as? IncompleteWork)?.continuationReason
  val incompleteWorkOutput: NormalizedFeatureTaskRuntimePhaseOutput?
    get() = (this as? IncompleteWork)?.normalizedOutput
  val boundaryBodyDeliveryContinuationReason: String?
    get() = (this as? BoundaryBodyDelivery)?.continuationReason

  companion object {
    fun settled(outcome: PhaseOutcome): AttemptResult = Settled(outcome)

    fun boundaryBodyDelivery(
      continuationReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ): AttemptResult = BoundaryBodyDelivery(continuationReason, fileManifest)

    fun incompleteWork(
      operatorReason: String,
      continuationReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
      normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    ): AttemptResult = IncompleteWork(operatorReason, continuationReason, fileManifest, normalizedOutput)

    fun unaccountedItems(
      phaseId: String,
      itemNoun: String,
      unaccountedRefs: List<String>,
      retryReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ): AttemptResult = FindingsOwed(
      kind = FindingsOwedKind.OMITTED,
      operatorReason = "Phase '$phaseId' left carried $itemNoun unaccounted for in its output: " +
        unaccountedRefs.joinToString(", ") + ".",
      retryReason = retryReason,
      refs = unaccountedRefs.toSet(),
      detail = null,
      fileManifest = fileManifest,
    )

    fun unresolvedFindings(
      unresolvedRefs: Set<String>,
      detail: String,
      retryReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
    ): AttemptResult = FindingsOwed(
      kind = FindingsOwedKind.UNRESOLVED,
      operatorReason = "Phase 'implement_fix' reported carried review findings still open after " +
        "its attempt: ${unresolvedRefs.joinToString(", ")}.",
      retryReason = retryReason,
      refs = unresolvedRefs,
      detail = detail,
      fileManifest = fileManifest,
    )

    fun retryableTerminal(
      operatorReason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
      failureDisposition: FeatureTaskRuntimeFailureDisposition,
    ): AttemptResult = RetryableTerminal(operatorReason, operatorReason, fileManifest, failureDisposition)
    fun schemaInvalid(args: SchemaInvalidArgs): AttemptResult = SchemaInvalid(
      operatorReason = args.operatorReason,
      retryReason = args.retryReason ?: args.operatorReason,
      fileManifest = args.fileManifest,
      rejectedOutput = args.rejectedOutput,
      malformedOutput = args.malformedOutput,
      correctiveRepairContext = args.correctiveRepairContext,
    )
  }
}

internal sealed interface PhaseOutcome {
  data class Completed(val output: FeatureTaskRuntimePhaseOutput) : PhaseOutcome
  data class Blocked(val reason: String) : PhaseOutcome

  data class Paused(val reason: String) : PhaseOutcome

  data class RegenerateProducer(val producerPhaseId: String) : PhaseOutcome

  val completedOutput: FeatureTaskRuntimePhaseOutput? get() = (this as? Completed)?.output

  val blockedReason: String? get() = (this as? Blocked)?.reason

  val pausedReason: String? get() = (this as? Paused)?.reason

  val regenerationTargetPhaseId: String? get() = (this as? RegenerateProducer)?.producerPhaseId

  companion object {
    fun completed(output: FeatureTaskRuntimePhaseOutput): PhaseOutcome = Completed(output)
    fun blocked(reason: String): PhaseOutcome = Blocked(reason)
    fun paused(reason: String): PhaseOutcome = Paused(reason)
    fun regenerateProducer(producerPhaseId: String): PhaseOutcome = RegenerateProducer(producerPhaseId)
  }
}

internal sealed interface GoalReviewRunPreparation {
  data object CarryForward : GoalReviewRunPreparation
  class Blocked(
    val reason: String,
    val failureDisposition: FeatureTaskRuntimeFailureDisposition,
  ) : GoalReviewRunPreparation
}

internal data class GoalReviewRunReady(val run: PhaseRun) : GoalReviewRunPreparation

const val READ_ONLY_PHASE_PROGRESS_IDLE_TIMEOUT_MINUTES = 30L

const val LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION =
  "rejected an upstream bounded planning projection at the launch seam"

const val OWNED_PATH_DELIMITER = '\u0000'

const val MAX_CHECKPOINT_OWNED_PATHS = 500
