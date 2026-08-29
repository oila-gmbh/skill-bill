package skillbill.application.featuretask

import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

internal data class RecordRejectionRegenerationEdge(
  val producer: String,
  val edge: FeatureTaskRuntimeBackwardEdge,
)

internal fun FeatureTaskRuntimeRunLoop.recordRejectionRegenerationEdge(
  consumer: String,
): RecordRejectionRegenerationEdge? {
  val producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER[consumer] ?: return null
  val edge = transitions.backwardEdges.firstOrNull {
    it.fromPhaseId == consumer && it.destinationPhaseId == producer &&
      it.triggeringVerdict == FeatureTaskRuntimeVerdict.RECORD_REJECTED
  } ?: return null
  if (producer !in transitions.forwardPhaseIds) return null
  return RecordRejectionRegenerationEdge(producer, edge)
}

internal sealed interface RecordRejectionEvidenceResolution {
  data class Ready(val evidence: ProducerOutputEvidence) : RecordRejectionEvidenceResolution
  data class Settled(val outcome: PhaseOutcome) : RecordRejectionEvidenceResolution
}

internal fun FeatureTaskRuntimeRunLoop.readProducerEvidenceForRecordRejection(
  args: ProducerEvidenceRecordRejectionArgs,
): RecordRejectionEvidenceResolution {
  val run = args.context.run
  val state = args.context.state
  val iteration = args.context.iteration
  val observability = args.context.observability
  val producer = args.producer
  val consumer = args.consumer
  val producingIteration =
    (state.outputFor(producer)?.iteration ?: state.recordFor(producer)?.attemptCount ?: 1)
      .coerceAtLeast(1)
  val producerAgentId = state.recordFor(producer)?.resolvedAgentId
    ?: return RecordRejectionEvidenceResolution.Settled(
      missingProducerAgentBlock(run, iteration, consumer, producer, observability),
    )
  return when (
    val producerRead = recorder.producerOutput(
      ProducerOutputQueryArgs(
        workflowId = request.workflowId,
        phaseId = producer,
        attempt = producingIteration,
        agentId = producerAgentId,
        dbOverride = request.dbPathOverride,
        generation = state.evidenceGeneration(producer),
      ),
    )
  ) {
    is FeatureTaskRuntimeProducerOutputRead.Found ->
      RecordRejectionEvidenceResolution.Ready(producerRead.evidence)
    is FeatureTaskRuntimeProducerOutputRead.Absent,
    is FeatureTaskRuntimeProducerOutputRead.Unreadable,
    -> RecordRejectionEvidenceResolution.Settled(
      missingProducerEvidenceBlock(
        MissingProducerEvidenceBlockArgs(
          run = run,
          iteration = iteration,
          consumer = consumer,
          producer = producer,
          producingIteration = producingIteration,
          producerRead = producerRead,
          observability = observability,
        ),
      ),
    )
  }
}

private fun FeatureTaskRuntimeRunLoop.missingProducerAgentBlock(
  run: PhaseRun,
  iteration: Int,
  consumer: String,
  producer: String,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome = blockInPhase(
  PhaseBlockRequest(
    run = run,
    attemptCount = iteration,
    reason = "Feature-task-runtime phase '$consumer' rejected the durable record produced by " +
      "'$producer', but the producing phase's resolved agent is unavailable, so exact raw " +
      "evidence cannot be scoped to a producer. The run blocks instead of fabricating a " +
      "rejected-output diagnostic.",
    observability = observability,
    failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ),
)

private data class MissingProducerEvidenceBlockArgs(
  val run: PhaseRun,
  val iteration: Int,
  val consumer: String,
  val producer: String,
  val producingIteration: Int,
  val producerRead: FeatureTaskRuntimeProducerOutputRead,
  val observability: FeatureTaskRuntimeRunObservability,
)

private fun FeatureTaskRuntimeRunLoop.missingProducerEvidenceBlock(
  args: MissingProducerEvidenceBlockArgs,
): PhaseOutcome {
  val evidenceClause = if (args.producerRead is FeatureTaskRuntimeProducerOutputRead.Unreadable) {
    "retained evidence for attempt ${args.producingIteration} exists and the diagnostic store " +
      "refused it (${args.producerRead.failureClass.wireValue}). The run blocks instead of " +
      "fabricating a rejected-output diagnostic from normalized workflow state."
  } else {
    "no retained evidence exists for attempt ${args.producingIteration}. The run blocks instead " +
      "of fabricating a rejected-output diagnostic from normalized workflow state."
  }
  return blockInPhase(
    PhaseBlockRequest(
      run = args.run,
      attemptCount = args.iteration,
      reason = "Feature-task-runtime phase '${args.consumer}' rejected the durable record " +
        "produced by '${args.producer}', but $evidenceClause",
      observability = args.observability,
      failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    ),
  )
}

internal fun FeatureTaskRuntimeRunLoop.quarantineRecordRejection(args: QuarantineRecordRejectionArgs): PhaseOutcome =
  quarantineRecordRejectionBody(args)
