package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhasePromptComposeInputs

internal fun composePhasePrompt(inputs: FeatureTaskRuntimePhasePromptComposeInputs): String =
  phasePromptSections(inputs).filter(String::isNotBlank).joinToString(separator = "\n\n")

internal fun phasePromptSections(inputs: FeatureTaskRuntimePhasePromptComposeInputs): List<String> {
  requireComposableInputs(
    issueKey = inputs.issueKey,
    priorSchemaFailure = inputs.priorSchemaFailure,
    priorTerminalFailure = inputs.priorTerminalFailure,
    priorFindingCoverage = inputs.priorFindingCoverage,
    correctiveRepairContext = inputs.correctiveRepairContext,
  )
  val effectiveContinuation = inputs.implementationContinuation.takeUnless { inputs.correctiveRepairContext != null }
  return phasePromptLeadingSections(inputs) +
    phasePromptMiddleSections(inputs) +
    phasePromptTrailingSections(inputs, effectiveContinuation)
}

private fun requireComposableInputs(
  issueKey: String,
  priorSchemaFailure: String?,
  priorTerminalFailure: String?,
  priorFindingCoverage: String?,
  correctiveRepairContext: skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext?,
) {
  require(issueKey.isNotBlank()) { "issueKey is required to compose a phase prompt." }
  require(correctiveRepairContext == null || !priorSchemaFailure.isNullOrBlank()) {
    "correctiveRepairContext requires a non-blank priorSchemaFailure; raw repair context belongs " +
      "only to schema-gate retries."
  }
  require(correctiveRepairContext == null || priorTerminalFailure.isNullOrBlank()) {
    "correctiveRepairContext cannot accompany a retryable-terminal failure; the correction kinds " +
      "must stay separate."
  }
  require(priorFindingCoverage.isNullOrBlank() || priorSchemaFailure.isNullOrBlank()) {
    "priorFindingCoverage cannot accompany a schema-gate failure; a receipt is either short of its " +
      "carried findings or rejected, never both in one correction."
  }
}
