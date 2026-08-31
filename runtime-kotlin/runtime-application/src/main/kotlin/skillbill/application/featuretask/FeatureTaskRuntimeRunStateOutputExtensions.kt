package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.requireAcceptedOutput

internal fun FeatureTaskRuntimeRunState.outputFor(phaseId: String): FeatureTaskRuntimePhaseOutput? =
  outputs.filter { it.phaseId == phaseId }.maxByOrNull { it.iteration }

internal fun FeatureTaskRuntimeRunState.outputCountFor(phaseId: String): Int = outputs.count { it.phaseId == phaseId }

internal fun FeatureTaskRuntimeRunState.nextIteration(phaseId: String): Int {
  val latestOutputIteration = outputs.filter { it.phaseId == phaseId }.maxOfOrNull { it.iteration } ?: 0
  val persistedAttempts = persistedAttemptCounts[phaseId] ?: 0
  return maxOf(persistedAttempts, latestOutputIteration) + 1
}

internal fun FeatureTaskRuntimeRunState.parsedOutput(output: FeatureTaskRuntimePhaseOutput?): Map<String, Any?>? {
  val payload = output?.payload ?: return null
  return parsedOutputsByPayload.getOrPut(payload) {
    output.normalizedOutput?.envelope
      ?: outputValidator.validatePhaseOutput(payload, sourceLabel = output.phaseId)
        .requireAcceptedOutput(output.phaseId)
        .normalizedOutput
        .envelope
  }
}
