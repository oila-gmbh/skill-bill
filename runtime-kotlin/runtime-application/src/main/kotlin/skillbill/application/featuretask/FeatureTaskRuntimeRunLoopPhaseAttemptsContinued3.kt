package skillbill.application.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.model.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.application.featuretask.model.FeatureTaskRuntimeProducerOutputRead
import skillbill.application.featuretask.model.ProducerOutputQueryArgs
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

@Inject
class FeatureTaskRuntimeRunLoopPhaseAttemptsContinued3 {
  internal fun recordRejectionRegenerationEdge(
    runLoop: FeatureTaskRuntimeRunLoop,
    consumer: String,
  ): FeatureTaskRuntimeRunLoopPhaseAttemptsContinued2.RecordRejectionRegenerationEdge? {
    val producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER[consumer] ?: return null
    val edge = runLoop.transitions.backwardEdges.firstOrNull {
      it.fromPhaseId == consumer && it.destinationPhaseId == producer &&
        it.triggeringVerdict == FeatureTaskRuntimeVerdict.RECORD_REJECTED
    } ?: return null
    if (producer !in runLoop.transitions.forwardPhaseIds) return null
    return FeatureTaskRuntimeRunLoopPhaseAttemptsContinued2.RecordRejectionRegenerationEdge(producer, edge)
  }

  internal sealed interface RecordRejectionEvidenceResolution {
    data class Ready(val evidence: ProducerOutputEvidence) : RecordRejectionEvidenceResolution
    data class Settled(val outcome: PhaseOutcome) : RecordRejectionEvidenceResolution
  }

  internal fun readProducerEvidenceForRecordRejection(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: ProducerEvidenceRecordRejectionArgs,
  ): RecordRejectionEvidenceResolution {
    val run = args.context.run
    val state = args.context.state
    val iteration = args.context.iteration
    val observability = args.context.observability
    val producer = args.producer
    val consumer = args.consumer
    val producingIteration =
      (runLoop.state.outputFor(producer)?.iteration ?: runLoop.state.recordFor(producer)?.attemptCount ?: 1)
        .coerceAtLeast(1)
    val producerAgentId = runLoop.state.recordFor(producer)?.resolvedAgentId
      ?: return RecordRejectionEvidenceResolution.Settled(
        missingProducerAgentBlock(
          runLoop,
          MissingProducerAgentBlockArgs(run, iteration, consumer, producer, runLoop.observability),
        ),
      )
    return when (
      val producerRead = runLoop.recorder.producerOutput(
        ProducerOutputQueryArgs(
          workflowId = runLoop.request.workflowId,
          phaseId = producer,
          attempt = producingIteration,
          agentId = producerAgentId,
          dbOverride = runLoop.request.dbPathOverride,
          generation = runLoop.state.evidenceGeneration(producer),
        ),
      )
    ) {
      is FeatureTaskRuntimeProducerOutputRead.Found ->
        RecordRejectionEvidenceResolution.Ready(producerRead.evidence)
      is FeatureTaskRuntimeProducerOutputRead.Absent,
      is FeatureTaskRuntimeProducerOutputRead.Unreadable,
      -> RecordRejectionEvidenceResolution.Settled(
        missingProducerEvidenceBlock(
          runLoop,
          MissingProducerEvidenceBlockArgs(
            run = run,
            iteration = iteration,
            consumer = consumer,
            producer = producer,
            producingIteration = producingIteration,
            producerRead = producerRead,
            observability = runLoop.observability,
          ),
        ),
      )
    }
  }

  private fun missingProducerAgentBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: MissingProducerAgentBlockArgs,
  ): PhaseOutcome = runLoop.collaborators.phaseAttempts.blockInPhase(
    runLoop,
    PhaseBlockRequest(
      run = args.run,
      attemptCount = args.iteration,
      reason = "Feature-task-runtime phase '${args.consumer}' rejected the durable record produced by " +
        "'${args.producer}', but the producing phase's resolved agent is unavailable, so exact raw " +
        "evidence cannot be scoped to a producer. The run blocks instead of fabricating a " +
        "rejected-output diagnostic.",
      observability = args.observability,
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

  private fun missingProducerEvidenceBlock(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: MissingProducerEvidenceBlockArgs,
  ): PhaseOutcome {
    val evidenceClause = if (args.producerRead is FeatureTaskRuntimeProducerOutputRead.Unreadable) {
      "retained evidence for attempt ${args.producingIteration} exists and the diagnostic store " +
        "refused it (${args.producerRead.failureClass.wireValue}). The run blocks instead of " +
        "fabricating a rejected-output diagnostic from normalized workflow runLoop.state."
    } else {
      "no retained evidence exists for attempt ${args.producingIteration}. The run blocks instead " +
        "of fabricating a rejected-output diagnostic from normalized workflow runLoop.state."
    }
    return runLoop.collaborators.phaseAttempts.blockInPhase(
      runLoop,
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

  internal fun quarantineRecordRejection(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: QuarantineRecordRejectionArgs,
  ): PhaseOutcome = quarantineRecordRejectionBody(runLoop, args)

  internal fun quarantineRecordRejectionBody(
    runLoop: FeatureTaskRuntimeRunLoop,
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
      (runLoop.state.outputFor(producer)?.iteration ?: runLoop.state.recordFor(producer)?.attemptCount ?: 1)
        .coerceAtLeast(1)
    val diagnosticWrite = writeQuarantineRejectedOutput(
      runLoop,
      WriteQuarantineRejectedOutputArgs(run, producingIteration, rejection, producer, producerEvidence),
    )
    appendQuarantineEntryForRejection(
      runLoop,
      QuarantineEntryWriteArgs(
        consumer = consumer,
        producer = producer,
        producingIteration = producingIteration,
        rejection = rejection,
        regenerationAttempt = (runLoop.state.edgeIterationCount(regeneration.edge.loopId) + 1).coerceAtLeast(1),
        iteration = iteration,
        diagnosticWrite = diagnosticWrite,
        producerEvidence = producerEvidence,
      ),
    )
    return PhaseOutcome.regenerateProducer(producer)
  }

  private fun writeQuarantineRejectedOutput(
    runLoop: FeatureTaskRuntimeRunLoop,
    args: WriteQuarantineRejectedOutputArgs,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    val run = args.run
    val producingIteration = args.producingIteration
    val rejection = args.rejection
    val producer = args.producer
    val producerEvidence = args.producerEvidence
    val rejectedPayload = producerEvidence.payload ?: byteArrayOf()
    return runLoop.collaborators.attemptSettlement.recordRejectedOutput(
      runLoop,
      RecordRejectedOutputArgs(
        run = run,
        iteration = producingIteration,
        rule = "reconciliation-${rejection.rejectionClass}",
        reason = runLoop.collaborators.recordRejection.retryRejectionReason(
          runLoop.collaborators.recordRejection.payloadFreeRejectionReason(
            "reconciliation-${rejection.rejectionClass}",
            runLoop.collaborators.recordRejection.rejectionPath(rejection.rejectionDetail),
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
        targeting = runLoop.collaborators.attemptSettlement.rejectedOutputTargeting(
          defaultRejectedOutputTargetingArgs(
            run,
            RejectedOutputTargetingOverrides(
              phaseId = producer,
              agentId = producerEvidence.agentId,
              model = producerEvidence.model,
              path = runLoop.collaborators.recordRejection.rejectionPath(rejection.rejectionDetail),
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

  private fun appendQuarantineEntryForRejection(runLoop: FeatureTaskRuntimeRunLoop, args: QuarantineEntryWriteArgs) {
    runLoop.recorder.appendQuarantineEntry(
      runLoop.request.workflowId,
      FeatureTaskRuntimeQuarantineEntry(
        producingPhaseId = args.producer,
        consumingPhaseId = args.consumer,
        producingIteration = args.producingIteration,
        rejectionClass = args.rejection.rejectionClass,
        rejectionDetail = runLoop.collaborators.recordRejection.payloadFreeRejectionReason(
          "reconciliation-${args.rejection.rejectionClass}",
          runLoop.collaborators.recordRejection.rejectionPath(args.rejection.rejectionDetail),
        ),
        regenerationAttempt = args.regenerationAttempt,
        quarantinedAtIteration = args.iteration.coerceAtLeast(1),
        diagnosticIdentity =
        (args.diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.identity,
        rejectedRecordByteSize = args.producerEvidence.byteSize,
        rejectedRecordSha256 = args.producerEvidence.sha256,
        diagnosticDegraded = args.diagnosticWrite is FeatureTaskRuntimeRejectedOutputWrite.Degraded,
      ),
      runLoop.request.dbPathOverride,
    )
  }
}
