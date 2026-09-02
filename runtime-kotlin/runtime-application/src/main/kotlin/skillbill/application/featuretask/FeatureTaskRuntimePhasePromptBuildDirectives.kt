package skillbill.application.featuretask

private const val BUILD_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, `bill-code-check`, " +
    "`./gradlew check`, `check " + "--" + "continue`, or the pack collect_all_full_gate_command. Those are not " +
    "this phase. "

fun runtimeOwnedBuildPhaseTask(packBuildCommand: String?): String {
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
    "re-runs the pack build command and mints the receipt — do not emit build_receipt, gate_run_count, " +
    "or any phase-output JSON."
}

fun buildGateTriagePhaseTask(packBuildCommand: String?): String {
  val gateLine = if (packBuildCommand.isNullOrBlank()) {
    "The dominant pack declares validation_gate.build_command for build proof."
  } else {
    "The pack build command is `$packBuildCommand` — do not run it during triage."
  }
  return "You are triaging an unparseable build gate failure blob before the first repair turn — do not spawn " +
    "delegated subagents. Read the gate stdout blob and repository files as needed; prefer read-only " +
    "inspection. $gateLine $BUILD_PHASE_FORBIDDEN_EXTRAS" +
    "Do not mutate the tree unless strictly needed to understand failures. Emit a recommended " +
    "validation_repair_plan as prose inside produced_outputs.value (JSON string) with suggested fields per " +
    "item: item_id, module, rule_or_task, location, failure_summary, fix_intent. Extra keys are allowed. " +
    "Return prose guidance only; do not fix code or emit build_receipt, gate_run_count, or gate evidence."
}
