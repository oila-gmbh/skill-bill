package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

private const val VALIDATE_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, or any other " +
    "repo-root checklist. Those commands are not this phase. "

internal val RUNTIME_OWNED_VALIDATE_PHASE_TASK: String =
  runtimeOwnedValidatePhaseTask(packCollectAllCommand = null)

internal fun runtimeOwnedValidatePhaseTask(packCollectAllCommand: String?): String {
  val gateLine = if (packCollectAllCommand.isNullOrBlank()) {
    "Run only the pack-declared validation_gate collect_all_full_gate_command and read that command's output."
  } else {
    "Run only this pack-declared collect-all command and read that command's output: `$packCollectAllCommand`."
  }
  return "You are the only validate agent for this step; the runtime will not launch another agent to continue your " +
    "work. $gateLine $VALIDATE_PHASE_FORBIDDEN_EXTRAS" +
    "Do not run `bill-code-check`. Fix every finding in this same session. Do not rerun that full collect-all " +
    "gate after each individual fix; you may run targeted `test`, `compileKotlin`, `detekt`, and `ktlintCheck` " +
    "while repairing when those tasks are part of that same pack gate. When the set looks clean, run that same " +
    "collect-all command once to confirm. Do not launch delegated subagents. Findings that share one root cause " +
    "are one fix, not several. Validation findings are repair work, not a reason to block the phase. Fix " +
    "findings at their root cause; never silence them with annotations, baselines, disabled rules, weakened " +
    "configuration, or skipped tests. After you signal complete, the runtime may run one cache-bypassing " +
    "verify. Emit a bounded validation_result containing validation_status, checks, and repository_checkpoint; " +
    "do not embed raw command output or telemetry."
}

internal val AGENT_RUN_VALIDATE_PHASE_TASK: String =
  "You are the only validate agent for this step; the runtime will not launch another agent to continue your " +
    "work. Run the repository validation gate through bill-code-check once, read that output, and fix every " +
    "finding in this same session. $VALIDATE_PHASE_FORBIDDEN_EXTRAS" +
    "Do not rerun the full gate, bill-code-check, or a cache-bypassing full " +
    "check after each individual finding; you may run targeted `test`, `compileKotlin`, `detekt`, and " +
    "`ktlintCheck` while repairing when those tasks are part of the routed pack checker. When the set looks " +
    "clean, run bill-code-check once to confirm. Do not " +
    "launch delegated subagents. Invoke bill-code-check for those gate runs — it auto-routes to the " +
    "pack-declared quality-check skill; never name a stack-specific quality-check skill such as " +
    "bill-kotlin-code-check. Findings that share one root cause are one fix, not several. Validation findings " +
    "are repair work, not a reason to block the phase. Fix findings at their root cause; never silence them " +
    "with annotations, baselines, disabled rules, weakened configuration, or skipped tests. Emit a bounded " +
    "validation_result containing validation_status, checks, and repository_checkpoint; do not embed raw " +
    "command output or telemetry."

internal fun absentValidationGateDegradationDirective(phaseId: String, agentRunValidateFallback: Boolean): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE || !agentRunValidateFallback) {
    return ""
  }
  return """
    ## Validation gate degradation
    The dominant platform pack declares no validation_gate. Validate falls back to agent-run
    behavior: invoke bill-code-check. This degradation is intentional and surfaced; do not treat
    absence of a runtime finding set as a clean pass. Agent-reported gate_run_count is never
    validation evidence.
  """.trimIndent()
}

internal fun phaseTaskDirective(
  phaseId: String,
  agentRunValidateFallback: Boolean = false,
  packCollectAllCommand: String? = null,
  packBuildCommand: String? = null,
): String {
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
    return runtimeOwnedBuildPhaseTask(packBuildCommand)
  }
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE && agentRunValidateFallback) {
    return AGENT_RUN_VALIDATE_PHASE_TASK
  }
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE) {
    return runtimeOwnedValidatePhaseTask(packCollectAllCommand)
  }
  return phaseDirectives[phaseId] ?: error("No phase directive for runtime phase '$phaseId'.")
}
