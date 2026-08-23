package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory

private const val VALIDATE_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, or any other " +
    "repo-root checklist. Those commands are not this phase. "

internal val RUNTIME_OWNED_VALIDATE_PHASE_TASK: String =
  validatePhaseTask(packCollectAllCommand = null, packGateDeclared = true)

internal fun validatePhaseTask(packCollectAllCommand: String?, packGateDeclared: Boolean): String {
  val collectAllLine = when {
    !packCollectAllCommand.isNullOrBlank() ->
      "Invoke bill-code-check for collect-all and confirmation. The dominant pack declares " +
        "validation_gate; its collect-all argv is `$packCollectAllCommand`. bill-code-check routes to " +
        "the pack quality-check skill, which must run exactly that argv for the initial collect-all " +
        "and for the one confirmation pass — do not rediscover a different full-suite command."
    packGateDeclared ->
      "Invoke bill-code-check for collect-all and confirmation. The dominant pack declares " +
        "validation_gate; run only that pack's collect_all_full_gate_command through the routed " +
        "pack quality-check skill — do not rediscover a different full-suite command."
    else ->
      "Invoke bill-code-check for collect-all and confirmation. It auto-routes to the pack-declared " +
        "quality-check skill; never name a stack-specific quality-check skill such as " +
        "bill-kotlin-code-check."
  }
  return "You are the only validate agent for this step — do not spawn delegated subagents. The runtime " +
    "may give you up to three repair turns against the remaining findings; each turn is another session " +
    "of this same agent. $collectAllLine Read that output, and fix every finding in this same session. " +
    VALIDATE_PHASE_FORBIDDEN_EXTRAS +
    "Do not rerun the full gate, bill-code-check, or a cache-bypassing full check after each individual " +
    "finding; you may run targeted `test`, `compileKotlin`, `detekt`, and `ktlintCheck` while repairing " +
    "when those tasks are part of the routed pack checker. When the set looks clean, run bill-code-check " +
    "once to confirm (same pack collect-all). Findings that share one root cause are one fix, not several. " +
    "Validation findings are repair work, not a reason to block the phase. Fix findings at their root " +
    "cause; never silence them with annotations, baselines, disabled rules, weakened configuration, or " +
    "skipped tests. After you stop, the runtime re-runs the pack gate and mints the receipt — do not emit " +
    "validation_result, gate_run_count, or any phase-output JSON."
}

internal fun absentValidationGateDegradationDirective(phaseId: String, agentRunValidateFallback: Boolean): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE || !agentRunValidateFallback) {
    return ""
  }
  return """
    ## Validation gate degradation
    The dominant platform pack declares no validation_gate. Validate falls back to agent-run
    bill-code-check routing only. This degradation is intentional and surfaced; do not treat
    absence of a runtime finding set as a clean pass. Agent-reported gate_run_count is never
    validation evidence.
  """.trimIndent()
}

internal fun phaseTaskDirective(
  phaseId: String,
  agentRunValidateFallback: Boolean = false,
  packCollectAllCommand: String? = null,
  packBuildCommand: String? = null,
  priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
): String {
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
    return runtimeOwnedBuildPhaseTask(packBuildCommand)
  }
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
    return validatePhaseTask(
      packCollectAllCommand = packCollectAllCommand,
      packGateDeclared = !agentRunValidateFallback,
    )
  }
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
    return auditPhaseTaskDirective(priorGapMemory)
  }
  return phaseDirectives[phaseId] ?: error("No phase directive for runtime phase '$phaseId'.")
}
