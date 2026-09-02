package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.model.FeatureTaskRuntimeProjectionRejection
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.workflow.gitops.repositoryCheckpointFingerprint
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.boundPriorGapNotes

@Inject
class FeatureTaskRuntimeRunLoopLaunchContinued2 {
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
          repositoryCheckpoint = runLoop.collaborators.outputVerificationContinued1.resolveRepositoryCheckpoint(
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
    // Audit closure state is owned by audit itself, not an upstream producer. Its schema rejection
    // remains a durable block because regenerating a producer cannot repair it.
    ClosedCriterionRefsReady(
      if (run.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
        runLoop.collaborators.phaseRunner.durablyClosedCriterionRefs()
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
    runLoop.collaborators.launchContinued3.prepareDeclaredLaunchBody(runLoop, args)

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
      runLoop.collaborators.launchContinued3.outputEnvelopeOf(output)
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
}
