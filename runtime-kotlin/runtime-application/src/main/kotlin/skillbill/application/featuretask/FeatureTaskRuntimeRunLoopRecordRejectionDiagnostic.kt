package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeProducerOutputRead
import skillbill.application.featuretask.model.ProducerOutputQueryArgs
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput

fun FeatureTaskRuntimeRunLoopRecordRejection.responseStringValues(
  runLoop: FeatureTaskRuntimeRunLoop,
  value: Any?,
): List<String> {
  val values = mutableListOf<String>()
  collectResponseStringValues(runLoop, value, values)
  return values.distinct()
}

fun FeatureTaskRuntimeRunLoopRecordRejection.collectResponseStringValues(
  runLoop: FeatureTaskRuntimeRunLoop,
  value: Any?,
  values: MutableList<String>,
) {
  when (value) {
    is String -> values += value
    is Map<*, *> -> value.values.forEach { nested -> collectResponseStringValues(runLoop, nested, values) }
    is Iterable<*> -> value.forEach { nested -> collectResponseStringValues(runLoop, nested, values) }
  }
}

internal fun FeatureTaskRuntimeRunLoopRecordRejection.attemptOnce(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: RecordRejectionAttemptArgs,
): AttemptResult {
  val run = args.context.run
  val state = args.context.state
  val iteration = args.context.iteration
  val observability = args.context.observability
  val priorCorrection = args.priorCorrection
  val phaseTokenAccumulator = args.phaseTokenAccumulator
  // The running write is what the IDE reads as current_model while the child is in flight, so it
  // stamps the directive the launch below is rendered from. The settling exits then clear it only
  // where the launch proved no child ever ran, via LaunchResult.childNeverLaunched.
  runLoop.collaborators.outputPersistence.persistPhase(
    runLoop,
    PersistPhaseArgs(
      write = PhaseStateWriteArgs(
        run = run,
        iteration = iteration,
        status = STATUS_RUNNING,
        finished = false,
        outputArtifact = runLoop.state.outputFor(run.phaseId)?.payload,
      ),
      launched = runLoop.collaborators.outputPersistence.launchedModelDirective(run),
    ),
  )
  val launch = runLoop.collaborators.launch.launchAndCapture(
    runLoop,
    PhaseAttemptContext(run, runLoop.state, iteration, observability),
    priorCorrection,
    phaseTokenAccumulator,
  )
  return settleRecordRejectionLaunchOutcome(runLoop, args, launch)
}

internal fun FeatureTaskRuntimeRunLoopRecordRejection.recordUnattributableRejectedEvidence(
  runLoop: FeatureTaskRuntimeRunLoop,
  run: PhaseRun,
  state: FeatureTaskRuntimeRunState,
  rejection: RecordRejection,
) {
  val detail = payloadFreeRejectionReason(
    "reconciliation-${rejection.rejectionClass}",
    rejectionPath(rejection.rejectionDetail),
  )
  val rejectedOutput = run.declaration.projectionDeclarations
    .asSequence()
    .map { it.producerIteration.phaseId }
    .distinct()
    .mapNotNull { phaseId -> state.outputFor(phaseId) }
    .firstOrNull()
  val evidence = rejectedOutput?.let { output ->
    unattributableProducerEvidence(runLoop, state, output)
  }
  evidence?.let { writeUnattributableRejectedEvidence(runLoop, run, rejection, detail, it) }
}

private fun FeatureTaskRuntimeRunLoopRecordRejection.unattributableProducerEvidence(
  runLoop: FeatureTaskRuntimeRunLoop,
  state: FeatureTaskRuntimeRunState,
  output: FeatureTaskRuntimePhaseOutput,
): ProducerOutputEvidence? {
  val agentId = state.recordFor(output.phaseId)?.resolvedAgentId ?: return null
  return when (
    val read = runLoop.recorder.producerOutput(
      ProducerOutputQueryArgs(
        workflowId = runLoop.request.workflowId,
        phaseId = output.phaseId,
        attempt = output.iteration.coerceAtLeast(1),
        agentId = agentId,
        dbOverride = runLoop.request.dbPathOverride,
        generation = state.evidenceGeneration(output.phaseId),
      ),
    )
  ) {
    is FeatureTaskRuntimeProducerOutputRead.Found -> read.evidence
    is FeatureTaskRuntimeProducerOutputRead.Absent,
    is FeatureTaskRuntimeProducerOutputRead.Unreadable,
    -> null
  }
}

private fun FeatureTaskRuntimeRunLoopRecordRejection.writeUnattributableRejectedEvidence(
  runLoop: FeatureTaskRuntimeRunLoop,
  run: PhaseRun,
  rejection: RecordRejection,
  detail: String,
  evidence: ProducerOutputEvidence,
) {
  val payload = evidence.payload ?: byteArrayOf()
  runLoop.collaborators.attemptSettlement.recordRejectedOutput(
    runLoop,
    RecordRejectedOutputArgs(
      run = run,
      iteration = evidence.attempt,
      rule = "reconciliation-${rejection.rejectionClass}",
      reason = retryRejectionReason(detail, rejection.rejectionDetail),
      captured = CapturedPhaseOutput(
        text = payload.decodeToString(),
        bytes = payload,
        truncated = evidence.payload == null,
        byteSize = evidence.byteSize,
        sha256 = evidence.sha256,
      ),
      targeting = runLoop.collaborators.attemptSettlement.rejectedOutputTargeting(
        defaultRejectedOutputTargetingArgs(
          run,
          RejectedOutputTargetingOverrides(
            phaseId = evidence.phaseId,
            agentId = evidence.agentId,
            model = evidence.model,
            path = rejectionPath(rejection.rejectionDetail),
            repairTurn = evidence.repairTurn,
          ),
        ),
      ),
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoopRecordRejection.unattributableRecordRejectionReason(
  consumerPhaseId: String,
  rejection: RecordRejection,
  producer: String?,
  detail: String,
): String = if (producer == null) {
  "Feature-task-runtime phase '$consumerPhaseId' rejected an upstream durable record " +
    "(${rejection.rejectionClass}) it cannot attribute to a producing phase, so no regeneration edge " +
    "applies; the run blocks durably. Recover the record out of band by deleting or migrating the " +
    "offending row. Detail: $detail"
} else {
  "Feature-task-runtime phase '$consumerPhaseId' rejected the durable record produced by '$producer', but " +
    "'$producer' is absent from this run's resolved pipeline (a goal-continuation truncation dropped it), " +
    "so it cannot be regenerated in-band; the run blocks durably. Recover the record out of band by " +
    "deleting or migrating the offending row. Detail: $detail"
}

internal fun FeatureTaskRuntimeRunLoopRecordRejection.settleRecordRejectionLaunchOutcome(
  runLoop: FeatureTaskRuntimeRunLoop,
  args: RecordRejectionAttemptArgs,
  launch: LaunchResult,
): AttemptResult {
  val run = args.context.run
  val state = args.context.state
  val iteration = args.context.iteration
  val observability = args.context.observability
  launch.providerLimitReason?.let { reason ->
    return AttemptResult.settled(
      runLoop.collaborators.phaseAttemptsContinued2.pauseAndPersistInPhase(
        runLoop,
        PauseAndPersistInPhaseArgs(run, iteration, reason, runLoop.observability, launch.fileManifest),
      ),
    )
  }
  launch.infraFailureReason?.let { reason ->
    runLoop.collaborators.attemptSettlement.persistChildProcessFailureOutput(
      runLoop,
      run,
      iteration,
      reason,
      launch.infraFailureChildOutput,
    )
    return AttemptResult.settled(
      runLoop.collaborators.phaseAttemptsContinued2.blockAndPersistInPhase(
        runLoop,
        phaseBlockArgs(
          run,
          iteration,
          reason,
          runLoop.observability,
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
      runLoop.collaborators.phaseAttemptsContinued2.settleRecordRejection(
        runLoop,
        SettleRecordRejectionArgs(run, runLoop.state, iteration, runLoop.observability, rejection),
      ),
    )
  }
  val fileManifest = requireNotNull(launch.fileManifest)
  return runLoop.collaborators.attemptSettlement.gateOutput(
    runLoop,
    GateOutputArgs(
      run = run,
      iteration = iteration,
      captured = requireNotNull(launch.capturedPhaseOutput),
      observability = runLoop.observability,
      fileManifest = fileManifest,
    ),
  )
}

const val OFF_VOCABULARY_VERDICT_OPEN = "off-vocabulary verdict '"
const val OFF_VOCABULARY_VERDICT_CLOSE_BOUNDARY = "' and no"
