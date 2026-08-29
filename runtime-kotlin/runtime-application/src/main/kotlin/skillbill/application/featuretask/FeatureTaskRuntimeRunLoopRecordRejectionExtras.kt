package skillbill.application.featuretask

import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput

internal fun FeatureTaskRuntimeRunLoop.recordUnattributableRejectedEvidence(
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
    unattributableProducerEvidence(state, output)
  }
  evidence?.let { writeUnattributableRejectedEvidence(run, rejection, detail, it) }
}

private fun FeatureTaskRuntimeRunLoop.unattributableProducerEvidence(
  state: FeatureTaskRuntimeRunState,
  output: FeatureTaskRuntimePhaseOutput,
): ProducerOutputEvidence? {
  val agentId = state.recordFor(output.phaseId)?.resolvedAgentId ?: return null
  return when (
    val read = recorder.producerOutput(
      ProducerOutputQueryArgs(
        workflowId = request.workflowId,
        phaseId = output.phaseId,
        attempt = output.iteration.coerceAtLeast(1),
        agentId = agentId,
        dbOverride = request.dbPathOverride,
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

private fun FeatureTaskRuntimeRunLoop.writeUnattributableRejectedEvidence(
  run: PhaseRun,
  rejection: RecordRejection,
  detail: String,
  evidence: ProducerOutputEvidence,
) {
  val payload = evidence.payload ?: byteArrayOf()
  recordRejectedOutput(
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
      targeting = rejectedOutputTargeting(
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

internal fun FeatureTaskRuntimeRunLoop.unattributableRecordRejectionReason(
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
