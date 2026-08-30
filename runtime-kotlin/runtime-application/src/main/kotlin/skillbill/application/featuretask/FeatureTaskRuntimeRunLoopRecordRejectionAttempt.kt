package skillbill.application.featuretask

internal fun FeatureTaskRuntimeRunLoop.settleRecordRejectionLaunchOutcome(
  args: RecordRejectionAttemptArgs,
  launch: LaunchResult,
): AttemptResult {
  val run = args.context.run
  val state = args.context.state
  val iteration = args.context.iteration
  val observability = args.context.observability
  launch.providerLimitReason?.let { reason ->
    return AttemptResult.settled(pauseAndPersistInPhase(run, iteration, reason, observability, launch.fileManifest))
  }
  launch.infraFailureReason?.let { reason ->
    persistChildProcessFailureOutput(run, iteration, reason, launch.infraFailureChildOutput)
    return AttemptResult.settled(
      blockAndPersistInPhase(
        phaseBlockArgs(
          run,
          iteration,
          reason,
          observability,
          payload = BlockAndPersistPayload(
            childNeverLaunched = launch.childNeverLaunched,
            fileManifest = launch.fileManifest,
          ),
        ).withDisposition(launch.failureDisposition),
      ),
    )
  }
  launch.recordRejection?.let { rejection ->
    return AttemptResult.settled(
      settleRecordRejection(run, state, iteration, observability, rejection),
    )
  }
  val fileManifest = requireNotNull(launch.fileManifest)
  return gateOutput(
    GateOutputArgs(
      run = run,
      iteration = iteration,
      captured = requireNotNull(launch.capturedPhaseOutput),
      observability = observability,
      fileManifest = fileManifest,
    ),
  )
}
