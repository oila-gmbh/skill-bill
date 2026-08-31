package skillbill.application.featuretask

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.taskruntime.model.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput

internal object FeatureTaskRuntimeRunLoopReviewDriverSettlement {
  fun FeatureTaskRuntimeRunLoop.retainRuntimeOwnedReviewEvidence(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    iteration: Int,
    outputText: String,
  ) {
    val outputBytes = outputText.encodeToByteArray()
    recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = request.workflowId,
        phaseId = run.phaseId,
        attempt = iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = run.modelDirective?.model ?: "unspecified",
        recordedAt = clock.instant(),
        byteSize = outputBytes.size.toLong(),
        sha256 = RejectedOutputDiagnosticService.sha256(outputBytes),
        payload = outputBytes,
        generation = state.evidenceGeneration(run.phaseId),
      ),
      run.request.dbPathOverride,
    )
  }

  fun FeatureTaskRuntimeRunLoop.persistReviewCompletionOutcome(args: PhaseReviewCompletionOutcomeArgs): PhaseOutcome? {
    return if (isGoalReviewRun(args.persistence.run)) {
      persistGoalReviewCompletion(args.persistence, args.normalizedOutput, args.acceptedOutput.repairEvidence)
    } else {
      persistStandaloneReviewCompletion(args.persistence, args.outputText, args.acceptedOutput)
    }
  }

  fun FeatureTaskRuntimeRunLoop.completeRuntimeOwnedReviewPhase(
    run: PhaseRun,
    iteration: Int,
    observability: FeatureTaskRuntimeRunObservability,
    normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
    acceptedOutput: AcceptedFeatureTaskRuntimePhaseOutput,
  ): PhaseOutcome {
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        acceptedOutput.repairEvidence,
      ),
    )
  }
}
