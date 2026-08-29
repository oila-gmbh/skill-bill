package skillbill.application.featuretask

import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry

internal fun FeatureTaskRuntimeRunLoop.quarantineRecordRejectionBody(
  args: QuarantineRecordRejectionArgs,
): PhaseOutcome {
  val run = args.context.run
  val state = args.context.state
  val iteration = args.context.iteration
  val rejection = args.rejection
  val regeneration = args.regeneration
  val producerEvidence = args.producerEvidence
  val consumer = run.phaseId
  val producer = regeneration.producer
  val producingIteration =
    (state.outputFor(producer)?.iteration ?: state.recordFor(producer)?.attemptCount ?: 1)
      .coerceAtLeast(1)
  val diagnosticWrite = writeQuarantineRejectedOutput(
    run,
    producingIteration,
    rejection,
    producer,
    producerEvidence,
  )
  appendQuarantineEntryForRejection(
    QuarantineEntryWriteArgs(
      consumer = consumer,
      producer = producer,
      producingIteration = producingIteration,
      rejection = rejection,
      regenerationAttempt = (state.edgeIterationCount(regeneration.edge.loopId) + 1).coerceAtLeast(1),
      iteration = iteration,
      diagnosticWrite = diagnosticWrite,
      producerEvidence = producerEvidence,
    ),
  )
  return PhaseOutcome.regenerateProducer(producer)
}

private fun FeatureTaskRuntimeRunLoop.writeQuarantineRejectedOutput(
  run: PhaseRun,
  producingIteration: Int,
  rejection: RecordRejection,
  producer: String,
  producerEvidence: ProducerOutputEvidence,
): FeatureTaskRuntimeRejectedOutputWrite {
  val rejectedPayload = producerEvidence.payload ?: byteArrayOf()
  return recordRejectedOutput(
    RecordRejectedOutputArgs(
      run = run,
      iteration = producingIteration,
      rule = "reconciliation-${rejection.rejectionClass}",
      reason = retryRejectionReason(
        payloadFreeRejectionReason(
          "reconciliation-${rejection.rejectionClass}",
          rejectionPath(rejection.rejectionDetail),
        ),
        rejection.rejectionDetail,
      ),
      captured = CapturedPhaseOutput(
        text = rejectedPayload.decodeToString(),
        bytes = rejectedPayload,
        truncated = producerEvidence.payload == null,
        byteSize = producerEvidence.byteSize,
        sha256 = producerEvidence.sha256,
      ),
      targeting = rejectedOutputTargeting(
        defaultRejectedOutputTargetingArgs(
          run,
          RejectedOutputTargetingOverrides(
            phaseId = producer,
            agentId = producerEvidence.agentId,
            model = producerEvidence.model,
            path = rejectionPath(rejection.rejectionDetail),
            repairTurn = producerEvidence.repairTurn,
          ),
        ),
      ),
    ),
  )
}

private data class QuarantineEntryWriteArgs(
  val consumer: String,
  val producer: String,
  val producingIteration: Int,
  val rejection: RecordRejection,
  val regenerationAttempt: Int,
  val iteration: Int,
  val diagnosticWrite: FeatureTaskRuntimeRejectedOutputWrite,
  val producerEvidence: ProducerOutputEvidence,
)

private fun FeatureTaskRuntimeRunLoop.appendQuarantineEntryForRejection(args: QuarantineEntryWriteArgs) {
  recorder.appendQuarantineEntry(
    request.workflowId,
    FeatureTaskRuntimeQuarantineEntry(
      producingPhaseId = args.producer,
      consumingPhaseId = args.consumer,
      producingIteration = args.producingIteration,
      rejectionClass = args.rejection.rejectionClass,
      rejectionDetail = payloadFreeRejectionReason(
        "reconciliation-${args.rejection.rejectionClass}",
        rejectionPath(args.rejection.rejectionDetail),
      ),
      regenerationAttempt = args.regenerationAttempt,
      quarantinedAtIteration = args.iteration.coerceAtLeast(1),
      diagnosticIdentity =
      (args.diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.identity,
      rejectedRecordByteSize = args.producerEvidence.byteSize,
      rejectedRecordSha256 = args.producerEvidence.sha256,
      diagnosticDegraded = args.diagnosticWrite is FeatureTaskRuntimeRejectedOutputWrite.Degraded,
    ),
    request.dbPathOverride,
  )
}
