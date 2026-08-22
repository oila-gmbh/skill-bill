package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

private const val BUILD_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, `bill-code-check`, " +
    "`./gradlew check`, `check " + "--" + "continue`, or the pack collect_all_full_gate_command. Those are not " +
    "this phase. "

internal fun runtimeOwnedBuildPhaseTask(packBuildCommand: String?): String {
  val gateLine = if (packBuildCommand.isNullOrBlank()) {
    "Run only the pack-declared validation_gate build_command and read that command's output."
  } else {
    "Run only this pack-declared build command and read that command's output: `$packBuildCommand`."
  }
  return "You are the only build agent for this step; the runtime will not launch another agent to " +
    "continue your work. $gateLine $BUILD_PHASE_FORBIDDEN_EXTRAS" +
    "This phase is compile/buildability proof only: no suite tests, no full check, no substitute " +
    "agent-run gate. Fix every finding in this same session. Do not rerun the pack build command " +
    "after each individual fix; targeted compile tasks are allowed while repairing when they are " +
    "part of that same pack gate. When the set looks clean, run that same build command once to " +
    "confirm. Do not launch delegated subagents. After you signal complete, the runtime may run one " +
    "cache-bypassing verify. Emit a bounded build_receipt containing validation_status, checks, " +
    "repository_checkpoint, gate_run_count, and gate_runs; do not embed raw command output."
}

internal fun nonBuildPhaseBuildOwnershipDirective(phaseId: String): String {
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
    return ""
  }
  return """
    ## Build ownership
    Only the build phase may run the pack build_command (`validation_gate.build_command`). This phase
    must not invoke compile-only proof as a substitute for its own work.
  """.trimIndent()
}
