package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION

internal fun FeatureTaskRuntimeRunLoop.launchPreparationRejected(
  args: LaunchPreparationRejectedArgs,
): LaunchPreparationRejected {
  recordLaunchSeamRejection(
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

internal fun FeatureTaskRuntimeRunLoop.prepareDeclaredLaunchBody(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  priorCorrection: PriorAttemptCorrection?,
  durablyClosedCriterionRefs: List<String>,
  context: LaunchRejectionMeasurementContext,
): LaunchPreparation = try {
  PreparedLaunchReady(
    prepareLaunch(
      run,
      state,
      priorCorrection,
      durablyClosedCriterionRefs,
      context.repositoryCheckpoint,
    ),
  )
} catch (error: InvalidFeatureTaskRuntimeHandoffProjectionError) {
  rejectedHandoffLaunch(run, state, error, context)
} catch (error: InvalidFeatureTaskRuntimePhaseBriefingFramingError) {
  rejectedBriefingLaunch(run, state, error, context)
} catch (error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError) {
  rejectedPlanningProjectionLaunch(run, state, error, context)
} catch (error: InvalidWorkflowStateSchemaError) {
  rejectedDurableBriefingLaunch(run, state, error, context)
}

private fun FeatureTaskRuntimeRunLoop.rejectedHandoffLaunch(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  error: InvalidFeatureTaskRuntimeHandoffProjectionError,
  context: LaunchRejectionMeasurementContext,
): LaunchPreparationRejected = launchPreparationRejected(
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

private fun FeatureTaskRuntimeRunLoop.rejectedBriefingLaunch(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  error: InvalidFeatureTaskRuntimePhaseBriefingFramingError,
  context: LaunchRejectionMeasurementContext,
): LaunchPreparationRejected = launchPreparationRejected(
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

private fun FeatureTaskRuntimeRunLoop.rejectedPlanningProjectionLaunch(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  error: InvalidFeatureTaskRuntimePlanningProjectionSchemaError,
  context: LaunchRejectionMeasurementContext,
): LaunchPreparationRejected {
  recordLaunchSeamRejection(
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

private fun FeatureTaskRuntimeRunLoop.rejectedDurableBriefingLaunch(
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  error: InvalidWorkflowStateSchemaError,
  context: LaunchRejectionMeasurementContext,
): LaunchPreparationRejected = launchPreparationRejected(
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
