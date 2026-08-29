package skillbill.application.featuretask

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.boundPriorGapNotes

internal fun FeatureTaskRuntimeRunLoop.resolveLaunchMeasurementContext(
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
        repositoryCheckpoint = resolveRepositoryCheckpoint(run),
      ),
    )
  } catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
    recordLaunchSeamRejection(
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

internal fun FeatureTaskRuntimeRunLoop.resolveDurablyClosedCriterionRefs(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  context: LaunchRejectionMeasurementContext,
): LaunchPreparation = try {
  // Audit closure state is owned by audit itself, not an upstream producer. Its schema rejection
  // remains a durable block because regenerating a producer cannot repair it.
  ClosedCriterionRefsReady(
    if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      durablyClosedCriterionRefs()
    } else {
      emptyList()
    },
  )
} catch (error: InvalidWorkflowStateSchemaError) {
  recordLaunchSeamRejection(
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

internal fun FeatureTaskRuntimeRunLoop.prepareDeclaredLaunch(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  priorCorrection: PriorAttemptCorrection?,
  durablyClosedCriterionRefs: List<String>,
  context: LaunchRejectionMeasurementContext,
): LaunchPreparation = prepareDeclaredLaunchBody(
  run,
  state,
  priorCorrection,
  durablyClosedCriterionRefs,
  context,
)

internal fun FeatureTaskRuntimeRunLoop.recordLaunchSeamRejection(args: LaunchSeamRejectionArgs) {
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
  recorder.recordProjectionRejection(
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

internal fun FeatureTaskRuntimeRunLoop.priorGapMemoryFor(
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
    outputEnvelopeOf(output)?.let(FeatureTaskRuntimeOutputVerification::auditProseValue)
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
      diagnostics.warning(
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

internal fun FeatureTaskRuntimeRunLoop.outputEnvelopeOf(output: FeatureTaskRuntimePhaseOutput): Map<String, Any?>? =
  output.normalizedOutput?.envelope?.takeIf { it.isNotEmpty() }
    ?: JsonSupport.parseObjectOrNull(output.payload)?.let(JsonSupport::jsonElementToValue)
      ?.let(JsonSupport::anyToStringAnyMap)

internal fun FeatureTaskRuntimeRunLoop.reconcileLaunch(
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
      // Only a failure before the process-start boundary proves no child ran; a timeout, an
      // interruption and a non-zero exit all happened after it, under the launched model. Both
      // flags are consulted because they are one fact reported two ways: the launcher adapter
      // rejects a disagreeing pair, and reading only one of them would trust the weaker signal.
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
