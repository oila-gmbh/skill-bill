package skillbill.application.featuretask

import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

/**
 * Validate Task-line specialization and goal-continuation validate-depth directives.
 *
 * Split from the general per-phase directive file because these functions form one cluster: they
 * only specialize the validate Task line and its depth / gate-ownership variants. Keeps
 * [FeatureTaskRuntimePhasePromptDirectives] under the file-level function budget after adding
 * write-time discipline directives there.
 */

/**
 * Goal-continuation validate-depth directive. Parallel to [goalContinuationDirective]: empty under
 * [ValidationDepth.FULL] (and non-validate phases) so today's Phase 6 Task text stays byte-for-byte;
 * under [ValidationDepth.BUILD_ONLY] it is the sole validate Task text (header swaps to it) and also
 * renders as a titled section that forbids tests and the full repository validation gate.
 *
 * When [agentRunValidateFallback] is true (pack declares no validation_gate), use the agent-run
 * build-only prose that still forbids the full gate while allowing the agent to drive compile/build.
 */
internal fun goalContinuationValidateDepthDirective(
  phaseId: String,
  validationDepth: ValidationDepth,
  agentRunValidateFallback: Boolean = false,
): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
    validationDepth != ValidationDepth.BUILD_ONLY
  ) {
    return ""
  }
  return if (agentRunValidateFallback) {
    AGENT_RUN_BUILD_ONLY_VALIDATE_DIRECTIVE_SECTION
  } else {
    BUILD_ONLY_VALIDATE_DIRECTIVE_SECTION
  }
}

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

internal const val BUILD_ONLY_VALIDATE_PHASE_TASK: String =
  "Prove compile/buildability of the changed modules only. Fix only compile/build failures from the " +
    "runtime-provided finding set. While validate state is findings_open, do not invoke the gate, any quality-check " +
    "skill, tests, `detekt`, `ktlintCheck`, `test`, `compileKotlin`, spotless, lint, dependency scanners, or " +
    "Gradle module tasks; allowed work is read, search, and source edits only. Signal full repair without running " +
    "any confirm check; the runtime alone runs one cache-bypassing verification gate. Do not introduce " +
    "suppressions, disable rules, or weaken configuration. Emit a bounded validation_result " +
    "containing validation_status, checks, and repository_checkpoint; do not embed raw command " +
    "output or telemetry."

private val BUILD_ONLY_VALIDATE_DIRECTIVE_SECTION: String =
  """
    ## Goal-continuation validate depth
    validation_depth=build_only. Prove compile/buildability only. Fix only compile/build failures from
    the runtime-provided finding set. While validate state is findings_open, do not invoke the gate or any
    quality-check skill. Do not run tests written during implement, do not execute test suites, and do
    not run `detekt`, `ktlintCheck`, `test`, `compileKotlin`, spotless, lint, or dependency scanners.
    Allowed work is read, search, and source edits only. Signal full repair without any confirm check;
    the runtime alone runs one cache-bypassing verification gate. While repairing compile/build failures, do
    not introduce suppressions, disable rules, or weaken configuration. Emit a bounded validation_result
    containing validation_status, checks, and repository_checkpoint; do not embed raw command output or telemetry.
  """.trimIndent()

/**
 * Agent-run FULL validate Task text used only when the dominant pack declares no validation_gate.
 * Surfaces the intentional degradation: the agent invokes bill-code-check; runtime does not own the gate.
 */
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

/** Agent-run BUILD_ONLY Task text for packs without a validation_gate declaration. */
internal const val AGENT_RUN_BUILD_ONLY_VALIDATE_PHASE_TASK: String =
  "Prove compile/buildability of the changed modules only. Fix only compile/build failures from one " +
    "compile/build run. While the finding set is open, do not invoke any check, test, compile, " +
    "format-task, or quality-check command, including tests, `detekt`, `ktlintCheck`, `test`, " +
    "`compileKotlin`, spotless, lint, dependency scanners, bill-code-check, or the full repository " +
    "validation gate; allowed work is read, search, and source edits only. Do not introduce " +
    "suppressions, disable rules, or weaken configuration. Emit a bounded validation_result " +
    "containing validation_status, checks, and repository_checkpoint; do not embed raw command " +
    "output or telemetry."

private val AGENT_RUN_BUILD_ONLY_VALIDATE_DIRECTIVE_SECTION: String =
  """
    ## Goal-continuation validate depth
    validation_depth=build_only. Prove compile/buildability only. Fix only compile/build failures.
    While the finding set is open, do not run tests written during implement, do not execute test
    suites, and do not run `detekt`, `ktlintCheck`, `test`, `compileKotlin`, spotless, lint,
    dependency scanners, bill-code-check, or the full repository validation gate. Allowed work is
    read, search, and source edits only. While repairing compile/build failures, do not introduce
    suppressions, disable rules, or weaken configuration. Batch compile/build repairs: read the
    complete finding set from one compile/build run, fix every finding at its root cause, then rerun
    once to verify. Emit a bounded validation_result containing validation_status, checks, and
    repository_checkpoint; do not embed raw command output or telemetry.
  """.trimIndent()

/** Surfaces the absent-gate degradation so agent-run validate is never a silent no-gate path. */
internal fun absentValidationGateDegradationDirective(phaseId: String, agentRunValidateFallback: Boolean): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE || !agentRunValidateFallback) {
    return ""
  }
  return """
    ## Validation gate degradation
    The dominant platform pack declares no validation_gate. Validate falls back to agent-run
    behavior: invoke bill-code-check (or compile/build only under build_only depth). This
    degradation is intentional and surfaced; do not treat absence of a runtime finding set as a
    clean pass. Agent-reported gate_run_count is never validation evidence.
  """.trimIndent()
}

/**
 * Selects the validate Task text from depth and gate ownership; every other phase uses
 * [phaseDirectives] unchanged. Runtime-owned findings directives apply only when a gate is declared;
 * [agentRunValidateFallback] restores the bill-code-check agent-run path for absent declarations.
 */
internal fun phaseTaskDirective(
  phaseId: String,
  validationDepth: ValidationDepth,
  agentRunValidateFallback: Boolean = false,
): String {
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE && agentRunValidateFallback) {
    return if (validationDepth == ValidationDepth.BUILD_ONLY) {
      AGENT_RUN_BUILD_ONLY_VALIDATE_PHASE_TASK
    } else {
      AGENT_RUN_VALIDATE_PHASE_TASK
    }
  }
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE &&
    validationDepth == ValidationDepth.BUILD_ONLY
  ) {
    return BUILD_ONLY_VALIDATE_PHASE_TASK
  }
  return phaseDirectives[phaseId] ?: error("No phase directive for runtime phase '$phaseId'.")
}
