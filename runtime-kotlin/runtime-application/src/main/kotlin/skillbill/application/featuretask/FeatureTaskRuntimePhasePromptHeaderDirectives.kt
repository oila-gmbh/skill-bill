package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.PhasePromptHeaderInputs
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory

internal const val PHASE_PROMPT_TEMPLATE_INDENT = "      "

internal val forwardPhaseOrder: String =
  FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
    .filterNot { it in FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds }
    .joinToString(" -> ")

internal fun operatorBlockRetryDirective(phaseId: String, retry: FeatureTaskRuntimeOperatorBlockRetry?): String {
  if (retry == null) return ""
  require(retry.phaseId == phaseId) {
    "Operator blocked-phase retry guidance for '${retry.phaseId}' cannot be delivered to phase '$phaseId'."
  }
  return """
    ## Operator-applied blocked-phase retry decision
    An operator reviewed the prior block and explicitly reopened this phase. Apply this decision:
    ${retry.reason}
    Re-evaluate the current repository state using this decision. Do not repeat the superseded block solely
    because of the prior interpretation. The governed acceptance criteria and output contract still apply.
  """.trimIndent()
}

internal fun phasePromptHeader(inputs: PhasePromptHeaderInputs): String {
  val label = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepLabels[inputs.phaseId] ?: inputs.phaseId
  val directive = phaseTaskDirective(
    inputs.phaseId,
    PhaseTaskDirectiveArgs(
      agentRunValidateFallback = inputs.agentRunValidateFallback,
      packCollectAllCommand = inputs.packCollectAllCommand,
      packBuildCommand = inputs.packBuildCommand,
      priorGapMemory = inputs.priorGapMemory,
      validationGateRepair = inputs.validationGateRepair,
      validationGateTriage = inputs.validationGateTriage,
    ),
  )
  return buildString {
    appendLine("You are executing exactly one phase of the EXPERIMENTAL skill-bill feature-task-runtime")
    appendLine("loop ($forwardPhaseOrder)")
    appendLine("for issue ${inputs.issueKey}. The runtime owns the loop; do not run other phases, do not open")
    appendLine("or continue any other skill-bill workflow, and do not call `skill-bill workflow continue`.")
    appendLine()
    appendLine("Phase: ${inputs.phaseId} ($label)")
    append("Task: ")
    append(directive.lineSequence().joinToString("\n") { it.removePrefix(PHASE_PROMPT_TEMPLATE_INDENT) })
  }
}

internal fun installedRuntimeAuthorityDirective(): String = """
  ## Installed runtime is the contract source
  This briefing was composed by the installed Skill Bill runtime that will validate your output.
  Use the contract_version, envelope fields, skills, packs, and commands it names.
  Do not read orchestration/contracts, Kotlin contract constants, test fixtures, or skills/ in
  this workspace to override them. When this repository is skill-bill, those files are the change
  under work, not the validator. If you must inspect a schema, inspect the installed runtime copy,
  never this checkout.
""".trimIndent()

internal fun ceremonyDirective(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String {
  val featureSize = FeatureTaskRuntimeFeatureSize.fromWire(briefing.featureSize)
  val scaling = FeatureTaskRuntimePhaseWorkflowQueries.ceremonyScaling(featureSize)
  val reviewScope = scaling.reviewScope.wireValue
  val phaseSpecific = when (briefing.phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN ->
      "Apply ${scaling.preplanCeremony.promptLabel}. Keep the gate real: identify concrete scope, " +
        "affected boundaries, risks, and unknowns at the requested depth."
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
      "The runtime owns ${scaling.reviewScope.promptLabel}. Keep the review gate real: inspect the implemented " +
        "change for defects and record concrete file references."
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
      "Apply ${scaling.auditCeremony.promptLabel}. Keep the audit gate real: verify acceptance " +
        "criteria, report concrete gaps, and attach a complete blast-radius-aware fix plan in each " +
        "gap note so implement can close the gap without opening a new one."
    else ->
      "Use the resolved feature size for ceremony expectations; all runtime gates remain mandatory."
  }
  return """
    ## Runtime ceremony scaling
    feature_size: ${featureSize.name}
    preplan_ceremony: ${scaling.preplanCeremony.wireValue}
    review_scope: $reviewScope
    audit_ceremony: ${scaling.auditCeremony.wireValue}
    $phaseSpecific
    Scaling changes scope and verbosity only; it must not skip or weaken review, audit, validation,
    schema, branch, history, commit, or PR gates.
  """.trimIndent()
}
