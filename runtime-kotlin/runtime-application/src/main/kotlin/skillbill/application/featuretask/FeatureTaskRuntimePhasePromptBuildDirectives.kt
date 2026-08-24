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
  return "You are the only build agent for this step — do not spawn delegated subagents. The runtime may " +
    "give you up to three repair turns against the remaining findings; each turn is another session of " +
    "this same agent. $gateLine $BUILD_PHASE_FORBIDDEN_EXTRAS" +
    "This phase is compile/buildability proof only: no suite tests, no full check, no substitute " +
    "agent-run gate. Address every open finding in the same turn. Do not rerun the pack build command " +
    "after each individual fix; targeted compile tasks are allowed while repairing when they are " +
    "part of that same pack gate. When the set looks clean, you may run that same build command once to " +
    "sanity-check. Never silence findings with @Suppress, @file:Suppress, baselines, disabled rules, " +
    "weakened configuration, or skipped tests — fix root causes instead. After you stop, the runtime " +
    "re-runs the pack build command and mints the receipt — do not emit build_receipt, " +
    "or any phase-output JSON."
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
