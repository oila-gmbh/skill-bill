package skillbill.application.featuretask

import skillbill.application.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.application.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.application.featuretask.validation.model.ValidationGateAgentTriageLauncher
import skillbill.application.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.application.featuretask.validation.model.ValidationGateCycleResult
import skillbill.application.featuretask.validation.model.ValidationGateCycleTerminalOutcome
import skillbill.application.featuretask.validation.model.ValidationGateResolution
import skillbill.application.featuretask.validation.model.ValidationGateTriageResult
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.ports.workflow.gitops.repositoryFingerprint
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

object FeatureTaskRuntimeRunLoopValidationGate {
  internal fun runDeclaredBuildGateCycle(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
  ): PhaseOutcome {
    val checkpoint = FeatureTaskRuntimeRunLoopValidationGate.resolveValidationGateCheckpoint(runLoop, run)
      ?: return PhaseOutcome.blocked(
        "Build gate cycle could not resolve a repository checkpoint fingerprint.",
      )
    val iteration = state.nextIteration(run.phaseId)
    persistBuildGateRunningPhase(runLoop, run, state, iteration, observability)?.let { return it }
    val context = phaseAttemptAccumulatorContext(run, state, iteration, observability, runLoop.phaseTokenAccumulator)
    val cycle = runLoop.buildGateCoordinator.execute(
      cycle = buildGateCycleRequest(runLoop, ValidationGateCycleRequestArgs(context, checkpoint)),
      onGateRunCount = { observability.validationGateProgress() },
    )
    return FeatureTaskRuntimeRunLoopValidationGate.settleBuildGateCycleResult(
      runLoop,
      SettleBuildGateCycleResultArgs(run, iteration, observability, checkpoint, cycle),
    )
  }

  internal fun settleRuntimeOwnedBuild(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val acceptedOutput = acceptRuntimeOwnedBuild(runLoop, run, outputText).getOrElse { error ->
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersistInPhase(
        runLoop,
        phaseBlockArgs(
          run,
          iteration,
          "Runtime-owned build settlement did not validate: ${error.message.orEmpty()}",
          observability,
        ),
      )
    }
    return persistRuntimeOwnedBuildCompletion(
      runLoop,
      PersistRuntimeOwnedBuildCompletionArgs(run, iteration, outputText, observability, acceptedOutput),
    )
  }

  private fun acceptRuntimeOwnedBuild(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    outputText: String,
  ): Result<AcceptedFeatureTaskRuntimePhaseOutput> = runCatching {
    val accepted = runLoop.outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId)
      .requireAcceptedOutput(run.phaseId)
    val buildReceipt = JsonCodec.anyToStringAnyMap(
      JsonCodec.anyToStringAnyMap(accepted.normalizedOutput.envelope["produced_outputs"])?.get("build_receipt"),
    )
    runLoop.buildReceiptValidator.validateBuildReceipt(buildReceipt ?: emptyMap(), sourceLabel = run.phaseId)
    accepted
  }

  private fun persistRuntimeOwnedBuildCompletion(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: PersistRuntimeOwnedBuildCompletionArgs,
  ): PhaseOutcome {
    val run = args.run
    val iteration = args.iteration
    val outputText = args.outputText
    val observability = args.observability
    val acceptedOutput = args.acceptedOutput
    val normalizedOutput = acceptedOutput.normalizedOutput
    val persisted = runLoop.recorder.recordCompletedPhase(
      FeatureTaskRuntimeRunLoopOutputPersistence.phaseStateRequest(
        runLoop,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(
            run = run,
            iteration = iteration,
            status = STATUS_COMPLETED,
            finished = true,
            outputArtifact = outputText,
          ),
          extras = PhaseStateRequestAttachments(
            normalizedOutput = normalizedOutput,
            repairEvidence = acceptedOutput.repairEvidence,
          ),
        ),
      ),
      run.request.dbPathOverride,
    )
    if (!persisted) {
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned build settlement could not be persisted.",
          observability = observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
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

  internal fun launchValidationGateTriage(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ValidationGateTriageArgs,
  ): ValidationGateTriageResult {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val iteration = args.context.attempt.iteration
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val findings = args.findings
    val triageRun = run.copy(validationGateFindings = findings, validationGateTriage = true)
    val attempt = FeatureTaskRuntimeRunLoopRecordRejection.attemptOnce(
      runLoop,
      recordRejectionAttemptArgs(
        PhaseAttemptContext(triageRun, runLoop.state, iteration, runLoop.observability),
        phaseTokenAccumulator = runLoop.phaseTokenAccumulator,
      ),
    )
    val settled = attempt.settledOutcome
    val completed = settled?.completedOutput
    return when {
      completed != null -> FeatureTaskRuntimeRunLoopValidationGate.extractValidationGateTriagePlan(completed)
      settled != null -> ValidationGateTriageResult.Empty
      else -> ValidationGateTriageResult.Empty
    }
  }

  internal fun buildGateCycleRequest(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ValidationGateCycleRequestArgs,
  ): ValidationGateCycleRequest = FeatureTaskRuntimeRunLoopValidationGate.validationGateCycleRequest(
    runLoop,
    args,
  ).copy(validationDepth = ValidationDepth.DEFAULT)

  internal fun persistBuildGateRunningPhase(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val runningPhaseState = FeatureTaskRuntimeRunLoopOutputPersistence.phaseStateRequest(
      runLoop,
      PhaseStateRequestArgs(
        write = PhaseStateWriteArgs(
          run = run,
          iteration = iteration,
          status = STATUS_RUNNING,
          finished = false,
          outputArtifact = null,
        ),
      ),
    )
    state.reserveReviewPass(runningPhaseState.reviewPassNumber)
    if (!runLoop.recorder.recordPhaseState(runningPhaseState, run.request.dbPathOverride)) {
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Build gate cycle could not persist running build phase before gate execution.",
          observability = observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
    observability.started(
      run.phaseId,
      run.resolvedAgent.resolvedAgentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
    )
    return null
  }

  internal fun settleBuildGateCycleResult(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: SettleBuildGateCycleResultArgs,
  ): PhaseOutcome {
    val run = args.run
    val iteration = args.iteration
    val observability = args.observability
    val checkpoint = args.checkpoint
    val cycle = args.cycle
    return when (cycle) {
      ValidationGateCycleResult.AbsentFallback ->
        FeatureTaskRuntimeRunLoopValidationGate.settleRuntimeOwnedBuild(
          runLoop,
          run,
          iteration,
          FeatureTaskRuntimeBuildGateCoordinator.runtimeOwnedBuildOutput(
            repositoryCheckpoint = checkpoint,
            measurements = emptyList(),
            checks = emptyList(),
          ).payload,
          observability,
        )
      is ValidationGateCycleResult.Terminal ->
        when (val terminal = cycle.outcome) {
          is ValidationGateCycleTerminalOutcome.Completed ->
            FeatureTaskRuntimeRunLoopValidationGate.settleRuntimeOwnedBuild(
              runLoop,
              run,
              iteration,
              terminal.output.payload,
              observability,
            )
          is ValidationGateCycleTerminalOutcome.Blocked ->
            FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
              runLoop,
              PhaseBlockRequest(
                run = run,
                attemptCount = iteration,
                reason = terminal.reason,
                observability = observability,
                failureDisposition = terminal.failureDisposition
                  ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
              ),
            )
        }
    }
  }

  fun looseOutputEnvelope(outputText: String): Map<String, Any?>? {
    val trimmed = outputText.trim()
    JsonCodec.parseObjectOrNull(trimmed)?.let {
      return JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it))
    }
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start !in 0..<end) return null
    return JsonCodec.parseObjectOrNull(trimmed.substring(start, end + 1))
      ?.let { JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it)) }
  }

  fun gateTriageCapturedProducedOutputs(outputText: String): Map<String, Any?> {
    val produced = looseOutputEnvelope(outputText)
      ?.let { JsonCodec.anyToStringAnyMap(it["produced_outputs"]) }
      ?: return emptyMap()
    return buildMap {
      produced["value"]?.let { put("value", it) }
      produced["validation_repair_plan"]?.let { put("validation_repair_plan", it) }
    }
  }

  internal fun gateRepairSegmentOutput(run: PhaseRun, iteration: Int): FeatureTaskRuntimePhaseOutput =
    FeatureTaskRuntimePhaseOutput(
      phaseId = run.phaseId,
      iteration = iteration,
      payload =
      """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"${run.phaseId}",""" +
        """"status":"completed","summary":"Gate repair segment.","produced_outputs":{}}""",
    )

  internal fun gateTriageSegmentOutput(
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
      payload = JsonCodec.mapToJsonString(payload),
    )
  }

  fun extractValidationGateTriagePlan(output: FeatureTaskRuntimePhaseOutput): ValidationGateTriageResult {
    val envelope = FeatureTaskRuntimeRunLoopLaunch.outputEnvelopeOf(output)
      ?: return ValidationGateTriageResult.Empty
    val produced = JsonCodec.anyToStringAnyMap(envelope["produced_outputs"])
      ?: return ValidationGateTriageResult.Empty
    FeatureTaskRuntimeRunLoopValidationGate.planFromProducedValue(produced["value"])?.let { return it }
    val directPlan = FeatureTaskRuntimeRunLoopValidationGate
      .extractTriagePlanProse(produced["validation_repair_plan"])
    return if (!directPlan.isNullOrBlank()) {
      ValidationGateTriageResult.Captured(directPlan)
    } else {
      ValidationGateTriageResult.Empty
    }
  }

  internal fun planFromProducedValue(value: Any?): ValidationGateTriageResult? {
    val valueText = value as? String ?: return null
    if (valueText.isBlank()) return null
    val inner = JsonCodec.parseObjectOrNull(valueText)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
    val planFromValue = inner?.let { extractTriagePlanProse(it["validation_repair_plan"]) }
    if (!planFromValue.isNullOrBlank()) {
      return ValidationGateTriageResult.Captured(planFromValue)
    }
    return if (inner == null) ValidationGateTriageResult.Captured(valueText) else null
  }

  internal fun extractTriagePlanProse(raw: Any?): String? = when (raw) {
    is String -> raw.takeIf { it.isNotBlank() }
    null -> null
    else -> JsonCodec.mapToJsonString(
      JsonCodec.anyToStringAnyMap(raw) ?: mapOf("validation_repair_plan" to raw),
    ).takeIf { it.isNotBlank() && it != "{}" && it != "[]" }
  }

  internal fun launchValidationGateRepair(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ValidationGateRepairArgs,
  ): ValidationGateAgentRepairResult {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val iteration = args.context.attempt.iteration
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val findings = args.findings
    val repairTurn = args.repairTurn
    val triagePlan = args.triagePlan
    val repairRun = run.copy(
      validationGateFindings = findings,
      validationGateRepairTurn = repairTurn,
      validationGateTriagePlan = triagePlan,
      validationGateRepair = true,
    )
    val attempt = FeatureTaskRuntimeRunLoopRecordRejection.attemptOnce(
      runLoop,
      recordRejectionAttemptArgs(
        PhaseAttemptContext(repairRun, runLoop.state, iteration, runLoop.observability),
        phaseTokenAccumulator = runLoop.phaseTokenAccumulator,
      ),
    )
    val settled = attempt.settledOutcome
    val completed = settled?.completedOutput
    return when {
      completed != null -> ValidationGateAgentRepairResult.Completed(completed)
      settled != null -> ValidationGateAgentRepairResult.Blocked(
        settled.blockedReason
          ?: settled.pausedReason
          ?: "Validation repair attempt runLoop.session.blocked.",
        failureDisposition = runLoop.recorder.loadPhaseRecords(run.request.workflowId, run.request.dbPathOverride)
          ?.get(run.phaseId)
          ?.failureDisposition,
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

  internal fun settleRuntimeOwnedValidation(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val acceptedOutput = runCatching {
      runLoop.outputValidator.validatePhaseOutput(
        outputText,
        sourceLabel = run.phaseId,
      ).requireAcceptedOutput(run.phaseId)
    }.getOrElse { error ->
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockAndPersistInPhase(
        runLoop,
        phaseBlockArgs(
          run,
          iteration,
          "Runtime-owned validation settlement did not validate: ${error.message.orEmpty()}",
          observability,
        ),
      )
    }
    return finishRuntimeOwnedValidation(
      RuntimeOwnedValidationFinishArgs(runLoop, run, iteration, outputText, acceptedOutput, observability),
    )
  }

  private fun finishRuntimeOwnedValidation(args: RuntimeOwnedValidationFinishArgs): PhaseOutcome {
    val runLoop = args.runLoop
    val run = args.run
    val iteration = args.iteration
    val outputText = args.outputText
    val acceptedOutput = args.acceptedOutput
    val observability = args.observability
    val normalizedOutput = acceptedOutput.normalizedOutput
    val persisted = runLoop.recorder.recordCompletedPhase(
      FeatureTaskRuntimeRunLoopOutputPersistence.phaseStateRequest(
        runLoop,
        PhaseStateRequestArgs(
          write = PhaseStateWriteArgs(
            run = run,
            iteration = iteration,
            status = STATUS_COMPLETED,
            finished = true,
            outputArtifact = outputText,
          ),
          extras = PhaseStateRequestAttachments(
            normalizedOutput = normalizedOutput,
            repairEvidence = acceptedOutput.repairEvidence,
          ),
        ),
      ),
      run.request.dbPathOverride,
    )
    if (!persisted) {
      return FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
        runLoop,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned validation settlement could not be persisted.",
          observability = observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
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

  internal fun validationChangedPaths(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): List<String> =
    FeatureTaskRuntimeRunLoopOutputVerification.resolveRepositoryCheckpoint(
      runLoop,
      run,
    )?.workingTreeOwnedPaths.orEmpty().distinct().sorted()

  internal fun packCollectAllCommand(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
      return null
    }
    return when (
      val resolution = runLoop.phaseGates.validationGateResolver.resolve(
        validationChangedPaths(
          runLoop,
          run,
        ),
      )
    ) {
      is ValidationGateResolution.Declared -> resolution.declaration.collectAllFullGateCommand.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }

  internal fun packBuildCommand(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String? {
    if (run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
      return null
    }
    val validationChangedPaths = FeatureTaskRuntimeRunLoopValidationGate
      .validationChangedPaths(runLoop, run)
    return when (val resolution = runLoop.phaseGates.validationGateResolver.resolve(validationChangedPaths)) {
      is ValidationGateResolution.Declared -> resolution.declaration.buildCommand?.joinToString(" ")
      is ValidationGateResolution.Absent -> null
      is ValidationGateResolution.Incompatible -> null
    }
  }

  internal fun runPhaseAttempts(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>? = null,
  ): PhaseOutcome {
    val agentId = run.resolvedAgent.resolvedAgentId
    var iteration = state.nextIteration(run.phaseId)
    val continuationSegmentCount = FeatureTaskRuntimeRunLoopPhaseAttempts
      .durableContinuationSegmentCount(runLoop, run)
    val nonOutputAttempts = FeatureTaskRuntimeRunLoopPhaseAttempts.durableNonOutputAttempts(runLoop, run)
    prepareFixLoopState(runLoop, run, state, observability)?.let { return it }
    val semanticIteration = (
      state.fixLoopIterationFor(run.phaseId, iteration) - continuationSegmentCount - nonOutputAttempts.size
      ).coerceAtLeast(1)
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
          verifierReentry = run.reentry?.let {
            FeatureTaskRuntimeRunLoopBackwardEdge.isLoopDestination(
              runLoop,
              it,
            )
          } == true,
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
      outcome = resolveFixLoopOutcome(
        runLoop,
        FixLoopOutcomeArgs(
          context = phaseAttemptAccumulatorContext(
            run,
            state,
            loop.iteration,
            observability,
            runLoop.phaseTokenAccumulator,
          ),
          loop = loop,
          agentId = agentId,
        ),
      )
    }
    return outcome
  }

  internal fun prepareFixLoopState(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val nonOutputAttempts = FeatureTaskRuntimeRunLoopPhaseAttempts.durableNonOutputAttempts(runLoop, run)
    val processFailures = nonOutputAttempts.filterNot(FeatureTaskRuntimeNonOutputAttempt::paused)
    val operatorReopened = FeatureTaskRuntimeRunLoopPhaseAttempts.operatorReopenedPhase(runLoop, run.phaseId)
    if (operatorReopened) state.restartAttemptBudget(run.phaseId)
    if (!operatorReopened) {
      FeatureTaskRuntimeAttemptBudgets
        .processFailureBlockReason(run.phaseId, processFailures.size, processFailures.lastOrNull()?.reason)
        ?.let { reason ->
          return FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
            runLoop,
            PhaseBlockRequest(
              run = run,
              attemptCount = state.nextIteration(run.phaseId),
              reason = reason,
              observability = observability,
              failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
            ),
          )
        }
    }
    return null
  }

  internal fun resolveFixLoopOutcome(runLoop: FeatureTaskRuntimeRunLoop, args: FixLoopOutcomeArgs): PhaseOutcome? {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val loop = args.loop
    val agentId = args.agentId
    val attempt = FeatureTaskRuntimeRunLoopRecordRejection.attemptOnce(
      runLoop,
      recordRejectionAttemptArgs(
        PhaseAttemptContext(run, runLoop.state, loop.iteration, runLoop.observability),
        priorCorrection = loop.priorCorrection,
        phaseTokenAccumulator = runLoop.phaseTokenAccumulator,
      ),
    )
    val context = FixLoopBranchContext(run, attempt, loop, runLoop.observability, agentId)
    val phaseAttempts = FeatureTaskRuntimeRunLoopPhaseAttempts
    return attempt.settledOutcome ?: when {
      attempt.incompleteWorkContinuationReason != null -> phaseAttempts.settleIncompleteWork(runLoop, context)
      attempt.boundaryBodyDeliveryContinuationReason != null ->
        phaseAttempts.settleBoundaryBodyDelivery(runLoop, context)
      attempt.malformedOutput -> phaseAttempts.settleMalformedOutput(runLoop, context)
      attempt.retryableTerminalRetryReason != null -> phaseAttempts.settleRetryableTerminal(runLoop, context)
      attempt.findingsOwedKind != null -> phaseAttempts.settleFindingsOwed(runLoop, context)
      else -> FeatureTaskRuntimeRunLoopPhaseAttempts.settleSemanticFailure(runLoop, context)
    }
  }

  internal fun runDeclaredValidationGateCycle(
    runLoop: FeatureTaskRuntimeRunLoop,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    phaseTokenAccumulator: MutableMap<String, Pair<Int, Int>>?,
  ): PhaseOutcome {
    val checkpoint = FeatureTaskRuntimeRunLoopValidationGate.resolveValidationGateCheckpoint(runLoop, run)
      ?: return PhaseOutcome.blocked(
        "Validation gate cycle could not resolve a repository checkpoint fingerprint.",
      )
    val iteration = state.nextIteration(run.phaseId)
    val context = phaseAttemptAccumulatorContext(run, state, iteration, observability, runLoop.phaseTokenAccumulator)
    val cycle = runLoop.validationGateCoordinator.execute(
      cycle = FeatureTaskRuntimeRunLoopValidationGate.validationGateCycleRequest(
        runLoop,
        ValidationGateCycleRequestArgs(context, checkpoint),
      ),
      onGateRunCount = { observability.validationGateProgress() },
    )
    return FeatureTaskRuntimeRunLoopValidationGate.settleValidationGateCycleResult(
      runLoop,
      SettleValidationGateCycleArgs(context, cycle),
    )
  }

  internal fun validationGateCycleRequest(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ValidationGateCycleRequestArgs,
  ): ValidationGateCycleRequest {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val iteration = args.context.attempt.iteration
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val validationDepth = run.request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT
    return ValidationGateCycleRequest(
      repoRoot = run.request.repoRoot,
      request = run.request,
      validationDepth = validationDepth,
      changedPaths = FeatureTaskRuntimeRunLoopValidationGate.validationChangedPaths(runLoop, run),
      repositoryCheckpoint = args.checkpoint,
      agentTriageLauncher = ValidationGateAgentTriageLauncher { findings ->
        FeatureTaskRuntimeRunLoopValidationGate.launchValidationGateTriage(
          runLoop,
          ValidationGateTriageArgs(
            args.context,
            findings,
          ),
        )
      },
      agentRepairLauncher = ValidationGateAgentRepairLauncher { findings, repairIteration, triagePlan ->
        FeatureTaskRuntimeRunLoopValidationGate.launchValidationGateRepair(
          runLoop,
          ValidationGateRepairArgs(
            context = args.context,
            findings = findings,
            repairTurn = repairIteration,
            triagePlan = triagePlan,
          ),
        )
      },
    )
  }

  internal fun resolveValidationGateCheckpoint(runLoop: FeatureTaskRuntimeRunLoop, run: PhaseRun): String? =
    runLoop.phaseGates.gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)

  internal fun settleValidationGateCycleResult(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: SettleValidationGateCycleArgs,
  ): PhaseOutcome {
    val run = args.context.attempt.run
    val state = args.context.attempt.state
    val observability = args.context.attempt.observability
    val phaseTokenAccumulator = args.context.phaseTokenAccumulator
    val iteration = args.context.attempt.iteration
    return when (args.cycle) {
      ValidationGateCycleResult.AbsentFallback ->
        FeatureTaskRuntimeRunLoopValidationGate.runPhaseAttempts(
          runLoop,
          run.copy(agentRunValidateFallback = true),
          runLoop.state,
          runLoop.observability,
          runLoop.phaseTokenAccumulator,
        )
      is ValidationGateCycleResult.Terminal -> {
        runLoop.observability.started(
          run.phaseId,
          run.resolvedAgent.resolvedAgentId,
          iteration,
          run.modelDirective,
          FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
        )
        when (val terminal = args.cycle.outcome) {
          is ValidationGateCycleTerminalOutcome.Completed ->
            FeatureTaskRuntimeRunLoopValidationGate.settleRuntimeOwnedValidation(
              runLoop,
              run,
              iteration,
              terminal.output.payload,
              runLoop.observability,
            )
          is ValidationGateCycleTerminalOutcome.Blocked ->
            FeatureTaskRuntimeRunLoopPhaseAttempts.blockInPhase(
              runLoop,
              PhaseBlockRequest(
                run = run,
                attemptCount = iteration,
                reason = terminal.reason,
                observability = runLoop.observability,
                failureDisposition = terminal.failureDisposition
                  ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
              ),
            )
        }
      }
    }
  }
}

private data class RuntimeOwnedValidationFinishArgs(
  val runLoop: FeatureTaskRuntimeRunLoop,
  val run: PhaseRun,
  val iteration: Int,
  val outputText: String,
  val acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
  val observability: FeatureTaskRuntimeRunObservability,
)
