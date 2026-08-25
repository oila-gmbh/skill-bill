package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

private const val VALIDATE_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, or any other " +
    "repo-root checklist. Those commands are not this phase. "

internal val RUNTIME_OWNED_VALIDATE_PHASE_TASK: String =
  validatePhaseTask(packCollectAllCommand = null, packGateDeclared = true)

private const val VALIDATE_REPAIR_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, `bill-code-check`, " +
    "`./gradlew check`, `check " + "--" + "continue`, or the pack collect_all_full_gate_command. Those are not " +
    "this repair turn. "

internal fun validateRepairPhaseTask(): String =
  "You are the only validate repair agent for this step — do not spawn delegated subagents. The open " +
    "findings are already listed in this briefing. Fix every listed finding at its root cause in this " +
    "session. $VALIDATE_REPAIR_FORBIDDEN_EXTRAS" +
    "Targeted `test`, `compileKotlin`, `detekt`, and `ktlintCheck` are allowed while repairing when those " +
    "tasks are part of the pack gate. Do not suppress findings. When the fixes look done, stop. Return " +
    "prose only."

internal fun validatePhaseTask(packCollectAllCommand: String?, packGateDeclared: Boolean): String {
  val collectAllLine = when {
    !packCollectAllCommand.isNullOrBlank() ->
      "Invoke bill-code-check. The pack collect-all command is `$packCollectAllCommand` — run exactly that."
    packGateDeclared ->
      "Invoke bill-code-check and run the pack collect_all_full_gate_command exactly."
    else ->
      "Invoke bill-code-check; it routes to the pack quality-check skill."
  }
  return "You are the only validate agent for this step — do not spawn delegated subagents. " +
    "$collectAllLine " +
    "Loop until green: run collect-all, read failures, fix them, run collect-all again to confirm. " +
    "If anything fails, repeat. If everything is green, stop — you are done. " +
    VALIDATE_PHASE_FORBIDDEN_EXTRAS +
    "Do not re-run collect-all after each individual finding; targeted repair tasks are fine while fixing. " +
    "Do not suppress findings. Return prose only."
}

internal fun absentValidationGateDegradationDirective(phaseId: String, agentRunValidateFallback: Boolean): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE || !agentRunValidateFallback) {
    return ""
  }
  return """
    ## Validation gate degradation
    The dominant platform pack declares no validation_gate. Validate falls back to agent-run
    bill-code-check routing only.
  """.trimIndent()
}

internal fun phaseTaskDirective(
  phaseId: String,
  inputs: PhaseTaskDirectiveInputs = PhaseTaskDirectiveInputs(),
): String = when (phaseId) {
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ->
    runtimeOwnedBuildPhaseTask(inputs.packBuildCommand)
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
    if (inputs.validationGateRepair) {
      validateRepairPhaseTask()
    } else {
      validatePhaseTask(
        packCollectAllCommand = inputs.packCollectAllCommand,
        packGateDeclared = !inputs.agentRunValidateFallback,
      )
    }
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
    auditPhaseTaskDirective(inputs.priorGapMemory)
  else -> phaseDirectives[phaseId] ?: error("No phase directive for runtime phase '$phaseId'.")
}

internal fun phaseRequestedAction(
  phaseId: String,
  inputs: PhaseTaskDirectiveInputs = PhaseTaskDirectiveInputs(),
): String {
  val base = phaseTaskDirective(phaseId = phaseId, inputs = inputs)
  if (
    phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX ||
    inputs.carriedFindingIds.isEmpty()
  ) {
    return base
  }
  return base +
    " Carried finding ids for this round: ${inputs.carriedFindingIds.sorted().joinToString(", ")}. " +
    "State your disposition for every listed finding id in your returned prose."
}
