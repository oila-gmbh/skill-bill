package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

internal const val RUNTIME_OWNED_VALIDATE_PHASE_TASK: String =
  "The runtime owns execution of the repository validation gate. You receive the complete finding set " +
    "from one collect-all gate run (module, rule or test identity, message, location) and must not invoke the gate " +
    "or any quality-check skill. Do not rediscover findings. While validate state is findings_open, do not invoke " +
    "any check, test, compile, format-task, or quality-check command, including the gate, `bill-code-check`, " +
    "`detekt`, `ktlintCheck`, `test`, `compileKotlin`, Gradle module tasks, pack checkers, or delegated subagent " +
    "checks; allowed work is read, search, and source edits only. Signal that the current set is fully repaired " +
    "without running any check to confirm first; that signal is the only way to leave findings_open. The runtime " +
    "alone runs one cache-bypassing verification gate after you signal repair. Never invoke the gate or any check " +
    "after an individual fix. Findings that share one root cause are one fix, not several. Validation findings are " +
    "repair work, not a reason to block the phase. Fix findings at their root cause; never silence them with " +
    "annotations, baselines, disabled rules, weakened configuration, or skipped tests. Emit a " +
    "bounded validation_result containing validation_status, checks, and repository_checkpoint; " +
    "do not embed raw command output or telemetry."

internal const val AGENT_RUN_VALIDATE_PHASE_TASK: String =
  "Run tests written during the implement phase, then run the repository validation gate " +
    "relevant to the change through bill-code-check once to collect the complete finding set. A gate " +
    "run costs minutes because it recompiles every dependent module and reruns their suites. While validate " +
    "state is findings_open, do not invoke any check, test, compile, format-task, or quality-check command, " +
    "including the gate, `bill-code-check`, `detekt`, `ktlintCheck`, `test`, `compileKotlin`, Gradle " +
    "module tasks, pack checkers, or delegated subagent checks; allowed work is read, search, and source " +
    "edits only. Signal full repair without any confirm check; that signal is the only way to leave findings_open. " +
    "Then run exactly one verification gate to confirm. Never rerun the gate, bill-code-check, or any targeted " +
    "command after an individual fix, and never rerun to rediscover findings the previous run already reported. " +
    "Findings that share one root cause are one fix, not several. Validation findings are repair work, not a " +
    "reason to block the phase. Invoke bill-code-check for that gate — it auto-routes to the pack-declared " +
    "quality-check skill; never name a stack-specific quality-check skill such as bill-kotlin-code-check. Fix " +
    "findings at their root cause; never silence them with annotations, baselines, disabled rules, weakened " +
    "configuration, or skipped tests. Emit a bounded validation_result containing validation_status, checks, and " +
    "repository_checkpoint; do not embed raw command output or telemetry."

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

internal fun phaseTaskDirective(phaseId: String, agentRunValidateFallback: Boolean = false): String {
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE && agentRunValidateFallback) {
    return AGENT_RUN_VALIDATE_PHASE_TASK
  }
  return phaseDirectives[phaseId] ?: error("No phase directive for runtime phase '$phaseId'.")
}
