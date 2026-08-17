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
    "from one gate run (module, rule or test identity, message, location) and must not invoke the gate " +
    "or any quality-check skill. Do not rediscover findings. Fix every finding at its root cause and " +
    "return; the runtime reruns the gate to confirm. Never invoke the gate after an individual fix. " +
    "Findings that share one root cause are one fix, not several. Validation findings are repair work, " +
    "not a reason to block the phase. Fix findings at their root cause; never silence them with " +
    "annotations, baselines, disabled rules, weakened configuration, or skipped tests. Emit a " +
    "bounded validation_result containing validation_status, checks, and repository_checkpoint; " +
    "do not embed raw command output or telemetry."

internal const val BUILD_ONLY_VALIDATE_PHASE_TASK: String =
  "Prove compile/buildability of the changed modules only. Fix only compile/build failures from the " +
    "runtime-provided finding set. Do not invoke the gate, any quality-check skill, tests, detekt, " +
    "spotless, lint, or dependency scanners. While repairing compile/build failures, do not introduce " +
    "suppressions, disable rules, or weaken configuration. Emit a bounded validation_result " +
    "containing validation_status, checks, and repository_checkpoint; do not embed raw command " +
    "output or telemetry."

private val BUILD_ONLY_VALIDATE_DIRECTIVE_SECTION: String =
  """
    ## Goal-continuation validate depth
    validation_depth=build_only. Prove compile/buildability only. Fix only compile/build failures from
    the runtime-provided finding set. Do not invoke the gate or any quality-check skill. Do not run tests
    written during implement, do not execute test suites, and do not run detekt, spotless, lint,
    or dependency scanners. While repairing compile/build failures, do not introduce suppressions,
    disable rules, or weaken configuration. Batch compile/build repairs: fix every finding at its
    root cause and return; the runtime reruns the compile/build gate to verify. Emit a bounded
    validation_result containing validation_status, checks, and repository_checkpoint; do not embed
    raw command output or telemetry.
  """.trimIndent()

/**
 * Agent-run FULL validate Task text used only when the dominant pack declares no validation_gate.
 * Surfaces the intentional degradation: the agent invokes bill-code-check; runtime does not own the gate.
 */
internal const val AGENT_RUN_VALIDATE_PHASE_TASK: String =
  "Run tests written during the implement phase, then run the repository validation gate " +
    "relevant to the change. A gate run costs minutes because it recompiles every dependent module " +
    "and reruns their suites, so batch the repair: read the complete finding set from one gate run, " +
    "fix every finding in it at its root cause, and only then run the gate again to verify. Never " +
    "rerun the gate after an individual fix, and never rerun it to rediscover findings the previous " +
    "run already reported. Rerun early only when a fix genuinely cannot be completed without fresh " +
    "gate output, and say which finding forced it. Findings that share one root cause are one fix, " +
    "not several. Validation findings are repair work, not a reason to block the phase. Invoke " +
    "bill-code-check for that gate — it auto-routes to the pack-declared quality-check skill; never " +
    "name a stack-specific quality-check skill such as bill-kotlin-code-check. Fix findings at their " +
    "root cause; never silence them with annotations, baselines, disabled rules, weakened " +
    "configuration, or skipped tests. Emit a " +
    "bounded validation_result containing validation_status, checks, and repository_checkpoint; " +
    "do not embed raw command output or telemetry."

/** Agent-run BUILD_ONLY Task text for packs without a validation_gate declaration. */
internal const val AGENT_RUN_BUILD_ONLY_VALIDATE_PHASE_TASK: String =
  "Prove compile/buildability of the changed modules only. Fix only compile/build failures. Do not " +
    "run tests, detekt, spotless, lint, dependency scanners, or the full bill-code-check / " +
    "repository validation gate. While repairing compile/build failures, do not introduce " +
    "suppressions, disable rules, or weaken configuration. Emit a bounded validation_result " +
    "containing validation_status, checks, and repository_checkpoint; do not embed raw command " +
    "output or telemetry."

private val AGENT_RUN_BUILD_ONLY_VALIDATE_DIRECTIVE_SECTION: String =
  """
    ## Goal-continuation validate depth
    validation_depth=build_only. Prove compile/buildability only. Fix only compile/build failures.
    Do not run tests written during implement, do not execute test suites, and do not run detekt,
    spotless, lint, dependency scanners, or the full bill-code-check / repository validation gate.
    While repairing compile/build failures, do not introduce suppressions, disable rules, or
    weaken configuration. Batch compile/build repairs the same way full validate batches gate
    repairs: read the complete finding set from one compile/build run, fix every finding at its
    root cause, then rerun once to verify. Emit a bounded validation_result containing
    validation_status, checks, and repository_checkpoint; do not embed raw command output or
    telemetry.
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
