package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.error.InvalidFeatureTaskRuntimePhaseBriefingFramingError
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.QUARANTINE_REJECTION_CLASS_PLANNING_PROJECTION

@Inject
class FeatureTaskRuntimeRunLoopLaunchProcessWait {
  fun outputEnvelopeOf(output: FeatureTaskRuntimePhaseOutput): Map<String, Any?>? =
    output.normalizedOutput?.envelope?.takeIf { it.isNotEmpty() }
      ?: JsonSupport.parseObjectOrNull(output.payload)?.let(JsonSupport::jsonElementToValue)
        ?.let(JsonSupport::anyToStringAnyMap)

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

  internal fun launchPreparationRejected(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: LaunchPreparationRejectedArgs,
  ): LaunchPreparationRejected {
    runLoop.collaborators.launchContinued2.recordLaunchSeamRejection(
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
        runLoop.collaborators.outputPersistence.prepareLaunch(
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
    runLoop.collaborators.launchContinued2.recordLaunchSeamRejection(
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
