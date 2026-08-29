package skillbill.application.featuretask

import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolved
import skillbill.application.evidence.FeatureTaskRuntimeSharedReviewEvidenceResolver
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeCheckpointScopeInput
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemoryRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.application.goalrunner.GoalSubtaskReviewSummaryReducer
import skillbill.application.goalrunner.UnaddressedFindingLedgerScope
import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseStateRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimePlanningStopDecision
import skillbill.application.featuretask.model.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.application.review.model.ParallelCodeReviewRequest
import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.toProjectionPayload
import skillbill.application.workflow.repoRoot
import skillbill.config.model.PhaseCompactionDirective
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.FeatureTaskRuntimeHandoffProjectionFailureKind
import skillbill.error.FeatureTaskRuntimePhaseOrderViolationError
import skillbill.error.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.install.model.InstallAgent
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.buildGoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.captureIndexState
import skillbill.ports.workflow.gitops.headCommitMessage
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.pathContentIdentities
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.ports.workflow.gitops.repositoryOwnedPaths
import skillbill.ports.workflow.gitops.restoreIndexState
import skillbill.ports.workflow.gitops.runtimePhaseChangedPathsBetweenCommits
import skillbill.ports.workflow.gitops.runtimePhaseHeadCommit
import skillbill.ports.workflow.gitops.stagePaths
import skillbill.ports.workflow.gitops.stagedPaths
import skillbill.review.context.model.ReviewContextBudgetPolicy
import skillbill.review.context.model.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.SpecIntentResolution
import skillbill.review.model.ReviewFindingVerdict
import skillbill.telemetry.estimation.estimateTokens
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimeQualityGateRouting
import skillbill.workflow.taskruntime.FeatureTaskRuntimeTransitionFunction
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_NO_PROGRESS
import skillbill.workflow.taskruntime.model.AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_STANDALONE_SUBTASK_ID
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapPause
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapProgress
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairProgressDecision
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewFinding
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.goal.model.GoalSubtaskBlockerDisposition
import skillbill.workflow.goal.model.GoalSubtaskOperatorDecision
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION
import skillbill.workflow.taskruntime.model.ReviewPassResolution
import skillbill.workflow.taskruntime.model.acceptanceCriterionRefsFor
import skillbill.workflow.taskruntime.model.boundPriorGapNotes
import skillbill.workflow.taskruntime.model.detectAuditRepairNonProgress
import skillbill.workflow.taskruntime.model.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.upsertRepairReceipt
import skillbill.workflow.taskruntime.model.validateDispositionCoverage
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.application.review.model.DiffResolutionException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputSourceLocation
import java.time.Instant
import skillbill.error.InvalidReviewContextSchemaError
import skillbill.review.context.model.ReviewContextBudgetExceededException
import skillbill.application.review.RuntimeOwnedReviewMode
import skillbill.application.review.model.StackDetectionException
import skillbill.application.goalrunner.StructuredGoalReviewFinding
import skillbill.workflow.taskruntime.model.UNPROVEN_REPOSITORY_FINGERPRINT
import skillbill.error.UnreadableSpecIntentProjectionError
import skillbill.application.review.model.UsageValidationException
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus


internal fun FeatureTaskRuntimeRunLoop.looseOutputEnvelope(outputText: String): Map<String, Any?>? {
    val trimmed = outputText.trim()
    JsonSupport.parseObjectOrNull(trimmed)?.let {
      return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it))
    }
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start !in 0..<end) return null
    return JsonSupport.parseObjectOrNull(trimmed.substring(start, end + 1))
      ?.let { JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it)) }
  }

internal fun FeatureTaskRuntimeRunLoop.gateTriageCapturedProducedOutputs(outputText: String): Map<String, Any?> {
    val produced = looseOutputEnvelope(outputText)
      ?.let { JsonSupport.anyToStringAnyMap(it["produced_outputs"]) }
      ?: return emptyMap()
    return buildMap {
      produced["value"]?.let { put("value", it) }
      produced["validation_repair_plan"]?.let { put("validation_repair_plan", it) }
    }
  }

internal fun FeatureTaskRuntimeRunLoop.gateRepairSegmentOutput(run: PhaseRun, iteration: Int): FeatureTaskRuntimePhaseOutput =
    FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload =
      """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"${run.phaseId}",""" +
        """"status":"completed","summary":"Gate repair segment.","produced_outputs":{}}""",
    )

internal fun FeatureTaskRuntimeRunLoop.gateTriageSegmentOutput(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
  ): FeatureTaskRuntimePhaseOutput {
    val captured = gateTriageCapturedProducedOutputs(outputText)
    val payload = mapOf(
      "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      "phase_id" to run.phaseId,
      "status" to "completed",
      "summary" to "Gate triage segment.",
      "produced_outputs" to captured,
    )
    return FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload = JsonSupport.mapToJsonString(payload),
    )
  }

internal fun FeatureTaskRuntimeRunLoop.extractValidationGateTriagePlan(output: FeatureTaskRuntimePhaseOutput): ValidationGateTriageResult {
    val envelope = outputEnvelopeOf(output) ?: return ValidationGateTriageResult.Empty
    val produced = JsonSupport.anyToStringAnyMap(envelope["produced_outputs"])
      ?: return ValidationGateTriageResult.Empty
    planFromProducedValue(produced["value"])?.let { return it }
    val directPlan = extractTriagePlanProse(produced["validation_repair_plan"])
    return if (!directPlan.isNullOrBlank()) {
      ValidationGateTriageResult.Captured(directPlan)
    } else {
      ValidationGateTriageResult.Empty
    }
  }

internal fun FeatureTaskRuntimeRunLoop.planFromProducedValue(value: Any?): ValidationGateTriageResult? {
    val valueText = value as? String ?: return null
    if (valueText.isBlank()) return null
    val inner = JsonSupport.parseObjectOrNull(valueText)
      ?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)
    val planFromValue = inner?.let { extractTriagePlanProse(it["validation_repair_plan"]) }
    if (!planFromValue.isNullOrBlank()) {
      return ValidationGateTriageResult.Captured(planFromValue)
    }
    return if (inner == null) ValidationGateTriageResult.Captured(valueText) else null
  }

internal fun FeatureTaskRuntimeRunLoop.extractTriagePlanProse(raw: Any?): String? = when (raw) {
    is String -> raw.takeIf { it.isNotBlank() }
    null -> null
    else -> JsonSupport.mapToJsonString(
      JsonSupport.anyToStringAnyMap(raw) ?: mapOf("validation_repair_plan" to raw),
    ).takeIf { it.isNotBlank() && it != "{}" && it != "[]" }
  }

internal fun FeatureTaskRuntimeRunLoop.launchValidationGateRepair(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
    findings: ValidationFindingSetProjection,
    repairTurn: Int,
    triagePlan: String?,
  ): ValidationGateAgentRepairResult {
    val repairRun = run.copy(
      validationGateFindings = findings,
      validationGateRepairTurn = repairTurn,
      validationGateTriagePlan = triagePlan,
      validationGateRepair = true,
    )
    val attempt = attemptOnce(
      repairRun,
      state,
      iteration,
      observability,
      priorCorrection = null,
      phaseTokenAccumulator,
    )
    val settled = attempt.settledOutcome
    val completed = settled?.completedOutput
    return when {
      completed != null -> ValidationGateAgentRepairResult.Completed(completed)
      settled != null -> ValidationGateAgentRepairResult.Blocked(
        settled.blockedReason
          ?: settled.pausedReason
          ?: "Validation repair attempt blocked.",
      )
      else -> ValidationGateAgentRepairResult.Completed(
        FeatureTaskRuntimePhaseOutput(
          phaseId = run.phaseId,
          iteration = iteration,
          payload =
          """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"${run.phaseId}",""" +
            """"status":"completed","summary":"Gate repair segment.","produced_outputs":{}}""",
        ),
      )
    }
  }

internal fun FeatureTaskRuntimeRunLoop.settleRuntimeOwnedValidation(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val acceptedOutput = runCatching {
      outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId).requireAcceptedOutput(run.phaseId)
    }.getOrElse { error ->
      return blockAndPersistInPhase(
        run,
        iteration,
        "Runtime-owned validation settlement did not validate: ${error.message.orEmpty()}",
        observability,
      )
    }
    val normalizedOutput = acceptedOutput.normalizedOutput
    val persisted = recorder.recordCompletedPhase(
      phaseStateRequest(
        run,
        iteration,
        STATUS_COMPLETED,
        finished = true,
        outputArtifact = outputText,
        normalizedOutput = normalizedOutput,
        repairEvidence = acceptedOutput.repairEvidence,
      ),
      run.request.dbPathOverride,
    )
    if (!persisted) {
      return blockAndPersistInPhase(
        run,
        iteration,
        "Runtime-owned validation settlement could not be persisted.",
        observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
      )
    }
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        acceptedOutput.repairEvidence,
      ),
    )
  }

internal fun FeatureTaskRuntimeRunLoop.validationChangedPaths(run: PhaseRun): List<String> =
    resolveRepositoryCheckpoint(run)?.workingTreeOwnedPaths.orEmpty().distinct().sorted()

internal fun FeatureTaskRuntimeRunLoop.packCollectAllCommand(run: PhaseRun): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
      return null
    }
    return when (val resolution = phaseGates.validationGateResolver.resolve(validationChangedPaths(run))) {
      is ValidationGateResolution.Declared -> resolution.declaration.collectAllFullGateCommand.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }

internal fun FeatureTaskRuntimeRunLoop.packBuildCommand(run: PhaseRun): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
      return null
    }
    return when (val resolution = phaseGates.validationGateResolver.resolve(validationChangedPaths(run))) {
      is ValidationGateResolution.Declared -> resolution.declaration.buildCommand?.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }

internal fun FeatureTaskRuntimeRunLoop.runPhaseAttempts(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): PhaseOutcome {
    val agentId = run.resolvedAgent.resolvedAgentId
    var iteration = state.nextIteration(run.phaseId)
    // Continuation segments advance the persisted attempt watermark like any other attempt, but they
    // stay on their own uncapped axis. In-process the two stay separate because settleIncompleteWork
    // never advances semanticIteration; across a process boundary only the watermark survives, so
    // without discounting the durable segments a resume would charge honest continuation work to the
    // semantic fix loop and block a run that never emitted invalid output. Read before the entry
    // check for exactly that reason.
    val continuationSegmentCount = durableContinuationSegmentCount(run)
    // Attempts that ended before the output gate — a dead process, or a launch the provider refused
    // at a usage limit — are discounted for the same reason continuation segments are: they emitted
    // nothing to repair, so charging them to the semantic budget both spends repair attempts on
    // unrepairable failures and reports the eventual block as invalid output. Only the failures are
    // charged to the process budget; a provider pause is not a failure of this run.
    val nonOutputAttempts = durableNonOutputAttempts(run)
    val processFailures = nonOutputAttempts.filterNot(FeatureTaskRuntimeNonOutputAttempt::paused)
    // An operator who explicitly reopened this phase has replaced every budget with their own
    // decision. Restart the baseline so the reopened phase actually relaunches instead of
    // re-surfacing the block the operator just acted on.
    val operatorReopened = operatorReopenedPhase(run.phaseId)
    if (operatorReopened) state.restartAttemptBudget(run.phaseId)
    // Clamped because a re-entry baseline may already have absorbed the same watermark the segment
    // count discounts; double-discounting must not drive the semantic index below its first attempt.
    val semanticIteration = (
      state.fixLoopIterationFor(run.phaseId, iteration) - continuationSegmentCount - nonOutputAttempts.size
      ).coerceAtLeast(1)
    if (!operatorReopened) {
      FeatureTaskRuntimeAttemptBudgets
        .processFailureBlockReason(run.phaseId, processFailures.size, processFailures.lastOrNull()?.reason)
        ?.let { reason ->
          return blockAndPersistInPhase(
            run,
            iteration,
            reason,
            observability,
            failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          )
        }
    }
    val crashResumed = state.resumedFromPriorProcess(run.phaseId)
    state.recordPhaseLaunched(run.phaseId)
    observability.started(
      run.phaseId,
      agentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry(
        resumed = iteration > 1 || state.hasPriorRecord(run.phaseId),
        startKind = featureTaskRuntimeStartContinuationKind(
          crashResumed = crashResumed,
          verifierReentry = run.reentry?.let { isLoopDestination(it) } == true,
          attemptCount = iteration,
        ),
      ),
    )
    var outcome: PhaseOutcome? = null
    val loop = PhaseAttemptLoopState(
      iteration = iteration,
      malformedAttemptCount = 0,
      outputGateFailures = 0,
      semanticIteration = semanticIteration,
      continuationSegmentCount = continuationSegmentCount,
    )
    while (outcome == null) {
      val attempt = attemptOnce(run, state, loop.iteration, observability, loop.priorCorrection, phaseTokenAccumulator)
      val context = FixLoopBranchContext(run, attempt, loop, observability, agentId)
      outcome = attempt.settledOutcome ?: when {
        attempt.incompleteWorkContinuationReason != null -> settleIncompleteWork(context)
        attempt.boundaryBodyDeliveryContinuationReason != null -> settleBoundaryBodyDelivery(context)
        attempt.malformedOutput -> settleMalformedOutput(context)
        // Its own branch, not the semantic-schema one: a retryable blocked/failed envelope is
        // schema-VALID, so prompting it with the schema-correction directive, reporting its block as a
        // schema-gate failure, or dispositioning it INVALID_OUTPUT would all misdescribe it.
        attempt.retryableTerminalRetryReason != null -> settleRetryableTerminal(context)
        // Before the semantic branch and after the terminal one: a receipt that still owes findings
        // is schema-valid, so charging it to the output-gate budget would block the round for work it
        // can still finish.
        attempt.findingsOwedKind != null -> settleFindingsOwed(context)
        else -> settleSemanticFailure(context)
      }
    }
    return outcome
  }

  /** Mutable per-phase fix-loop bookkeeping, held together so the branch handlers can advance it. */
