package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.validation.model.ValidationFindingSetProjection
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory

private const val VALIDATE_PHASE_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, or any other " +
    "repo-root checklist. Those commands are not this phase. "

val RUNTIME_OWNED_VALIDATE_PHASE_TASK: String =
  validatePhaseTask(packCollectAllCommand = null, packGateDeclared = true)

private const val VALIDATE_REPAIR_FORBIDDEN_EXTRAS: String =
  "Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, or any other " +
    "repo-root checklist. Those commands are not this repair turn. "

internal const val VALIDATE_REPAIR_BATCH_LOOP: String =
  "Before editing, copy the open findings into a numbered free-form checklist (file, rule, one-line " +
    "fix intent). Work in batches: fix one shared root cause or a coherent group of findings, then " +
    "re-run the pack collect_all_full_gate_command (via `bill-code-check` or the same argv directly) to " +
    "refresh the open set. Repeat check → fix-batch → check until green or you have used 3 full " +
    "collect-all runs in this session (the first discovery run and every refresh/confirm count). Never " +
    "run a full collect-all once per individual finding. Between full collect-all runs you may use " +
    "targeted proofs: project-wide `./gradlew spotlessApply` from the Gradle root for spotless/format " +
    "findings (never module-scoped `:module:spotlessApply`); module-scoped `detekt`, `ktlintCheck`, " +
    "`spotlessCheck`, `spotlessKotlinCheck`, `compileKotlin`, or `test` when the finding names that task; " +
    "read-only inspection anytime. Detekt threshold hits (TooManyFunctions, CyclomaticComplexMethod, " +
    "LongMethod) need structural refactors — extract helpers or move code to a sibling file; do not add " +
    "@Suppress. "

fun validateRepairPhaseTask(): String =
  "You are the only validate repair agent for this step — do not spawn delegated subagents. The runtime " +
    "already ran the pack collect-all gate and listed the open findings in this briefing. Prefer finishing " +
    "in this single session with the check → fix-batch → check loop below; the runtime may still give you " +
    "up to three repair turns if this session stops with findings open. $VALIDATE_REPAIR_FORBIDDEN_EXTRAS" +
    VALIDATE_REPAIR_BATCH_LOOP +
    "Stop when the pack collect-all is green or the 3-run budget is spent. After you stop, the runtime " +
    "re-runs the pack gate and mints the receipt — agent-green is not the receipt. Never silence findings " +
    "with annotations, baselines, disabled rules, weakened configuration, or skipped tests; fix root " +
    "causes instead. Return prose only; do not emit validation_result, gate_run_count, or any phase-output " +
    "JSON."

fun validateGateTriagePhaseTask(): String =
  "You are triaging an unparseable validation gate failure blob before the first repair turn — do not spawn " +
    "delegated subagents. Read the gate stdout blob and repository files as needed to understand failures; " +
    "prefer read-only inspection. Do not run `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, " +
    "`bill-code-check`, `./gradlew check`, `check " + "--" + "continue`, or the pack collect_all_full_gate_command. " +
    "Do not mutate the tree unless strictly needed to understand failures. Emit a recommended " +
    "validation_repair_plan as prose inside produced_outputs.value (JSON string) with suggested fields per " +
    "item: item_id, module, rule_or_task, location, failure_summary, fix_intent. Extra keys are allowed. " +
    "Return prose guidance only; do not fix code or emit validation_result, gate_run_count, or gate evidence."

fun validatePhaseTask(packCollectAllCommand: String?, packGateDeclared: Boolean): String {
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
  return "You are the only validate agent for this step — do not spawn delegated subagents. Prefer " +
    "finishing in this single session with the check → fix-batch → check loop below; the runtime may still " +
    "give you up to three repair turns if needed. $collectAllLine Read that output, then keep looping " +
    "until green or the session collect-all budget is spent. " +
    VALIDATE_PHASE_FORBIDDEN_EXTRAS +
    VALIDATE_REPAIR_BATCH_LOOP +
    "Findings that share one root cause are one fix, not several. Validation findings are repair work, " +
    "not a reason to block the phase. Fix findings at their root cause; never silence them with " +
    "annotations, baselines, disabled rules, weakened configuration, or skipped tests. After you stop, " +
    "the runtime re-runs the pack gate and mints the receipt — agent-green is not the receipt; do not " +
    "emit validation_result, gate_run_count, or any phase-output JSON."
}

fun absentValidationGateDegradationDirective(phaseId: String, agentRunValidateFallback: Boolean): String {
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

internal data class PhaseTaskDirectiveArgs(
  val agentRunValidateFallback: Boolean = false,
  val packCollectAllCommand: String? = null,
  val packBuildCommand: String? = null,
  val priorGapMemory: FeatureTaskRuntimePriorGapMemory? = null,
  val validationGateRepair: Boolean = false,
  val validationGateTriage: Boolean = false,
  val acceptanceCriteria: List<String> = emptyList(),
  val auditGapImplement: Boolean = false,
)

internal fun phaseTaskDirective(phaseId: String, args: PhaseTaskDirectiveArgs = PhaseTaskDirectiveArgs()): String =
  when (phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD -> when {
      args.validationGateTriage -> buildGateTriagePhaseTask(args.packBuildCommand)
      else -> runtimeOwnedBuildPhaseTask(args.packBuildCommand)
    }
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> when {
      args.validationGateTriage -> validateGateTriagePhaseTask()
      args.validationGateRepair -> validateRepairPhaseTask()
      else -> validatePhaseTask(
        packCollectAllCommand = args.packCollectAllCommand,
        packGateDeclared = !args.agentRunValidateFallback,
      )
    }
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ->
      auditPhaseTaskDirective(args.priorGapMemory, args.acceptanceCriteria)
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT ->
      implementPhaseTaskDirective(args.auditGapImplement, args.acceptanceCriteria)
    else -> phaseDirectives[phaseId] ?: error("No phase directive for runtime phase '$phaseId'.")
  }

fun gateRepairNoOutputSchemaDirective(phaseId: String, triage: Boolean = false): String {
  if (triage) {
    return """
      ## Gate triage — optional capture surface, no phase-output schema
      This launch triages an unparseable gate blob before the first repair turn for the runtime-owned `$phaseId` gate.
      Do not emit a Required final output JSON object, build_receipt, validation_receipt, gate_run_count, or any other
      phase receipt or gate evidence. Do not spawn delegated subagents. Read the blob and cited paths.
      When you can recommend a repair shape, you may emit produced_outputs.value (a JSON string) carrying
      validation_repair_plan prose with suggested fields per item: item_id, module, rule_or_task, location,
      failure_summary, fix_intent. Malformed or missing capture is fine; repair still runs without it.
    """.trimIndent()
  }
  return """
  ## Gate repair — prose only, no phase-output schema
  This launch is a repair turn for the runtime-owned `$phaseId` gate. Do not emit a Required final
  output JSON object, build_receipt, validation_receipt, gate_run_count, or any other phase envelope.
  Do not spawn delegated subagents. Work in this single agent session in ordinary prose.

  The runtime already ran the pack command and parsed the failures listed in this briefing. Prefer
  finishing here with check → fix-batch → check (max 3 full collect-all runs this session). The
  runtime re-runs the pack gate after you stop for the receipt, and it may give you up to three
  repair turns if findings remain.

  Before editing, do brief reasoned planning in prose for each finding (or for a shared root cause
  that covers several). Scale the plan to the finding:
  - Small / obvious: a few lines of due diligence, then fix.
  - Complex: a real short plan — blast radius, surrounding callers/contracts you checked, whether
    the change can introduce new bugs, and how you will keep the fix local.

  No defined plan schema. Do the thinking, then edit a batch, re-run the pack collect-all to refresh,
  and repeat until green or the session budget is spent. Between full collect-all runs you may use
  targeted proofs (for example project-wide `./gradlew spotlessApply`, or the module-scoped task
  named in the finding). Never run a full collect-all once per individual finding.
  Never silence findings with @Suppress, @file:Suppress, baselines, disabled rules, weakened
  configuration, or skipped tests — fix the root cause instead.
  """.trimIndent()
}

fun validationGateFindingsDirective(
  phaseId: String,
  findings: ValidationFindingSetProjection?,
  triagePlan: String?,
): String {
  if (findings == null) return ""
  val (sectionTitle, preamble) = when (phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> Pair(
      "## Runtime validation gate findings",
      "A prior gate run parsed these items. They are the starting open set for this repair turn — " +
        "fix batches and re-run the pack collect-all to refresh (shared root causes may collapse " +
        "several into one change). Do not run `skill-bill validate`, `npx agnix`, or other repo-root " +
        "checklists. $VALIDATE_REPAIR_BATCH_LOOP" +
        "Do not spawn delegated subagents.",
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD -> Pair(
      "## Runtime build gate findings",
      "A prior gate run parsed these items. They are the full open set for this repair turn — fix " +
        "every one in this session (shared root causes may collapse several into one change). Run only " +
        "the pack-declared build command when you need console detail. Do not run `skill-bill " +
        "validate`, `bill-code-check`, `./gradlew check`, `check " + "--" + "continue`, or the pack " +
        "collect_all_full_gate_command. Do not spawn delegated subagents.",
    )
    else -> return ""
  }
  val lines = buildList {
    add(sectionTitle)
    add(preamble)
    findings.findings.forEachIndexed { index, finding ->
      add(
        "${index + 1}. module=${finding.module} id=${finding.ruleOrTestId} " +
          "location=${finding.location ?: "<unknown>"} message=${finding.message}",
      )
    }
    if (!triagePlan.isNullOrBlank()) {
      add("## Triage working notes")
      add(triagePlan)
    }
  }
  return lines.joinToString("\n")
}

fun auditNoEarlierAuditLine(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String =
  if (briefing.priorGapMemory == null) {
    "      Every audit re-checks every listed criterion from scratch against the tree, so there is no\n" +
      "      earlier audit to account for and nothing to carry forward except the notes you emit now.\n"
  } else {
    "      Every audit re-checks every listed criterion from scratch against the tree; when this\n" +
      "      briefing carries prior-gap memory, earlier audit value strings in prior_audit_values are\n" +
      "      context you must account for, and a repeated criterion needs an explicit re-justification (below).\n"
  }

fun auditRoundScopeAddendum(briefing: FeatureTaskRuntimePhaseLaunchBriefing): String {
  val memoryBlock = briefing.priorGapMemory?.let { memory ->
    buildString {
      append("\n      Prior-gap memory (round ${memory.round}): prior audit value strings:\n")
      memory.priorAuditValues.forEach { value -> append("        - $value\n") }
      append("      When a gap repeats a criterion already named in a prior audit value, require explicit\n")
      append("      re-justification: name what the prior implement claimed and why the tree still fails it.\n")
    }
  }.orEmpty()
  val auditProse = briefing.handoffEnvelope.projections
    .firstOrNull { it.projectionName == "audit_prose" }
    ?.fields
    ?.firstOrNull { it.name == "value" }
    ?.value
    ?.let { (it as? FeatureTaskRuntimeHandoffProjectionValue.Text)?.text }
  val scopeBlock = if (auditProse.isNullOrBlank()) {
    ""
  } else {
    "\n      The previous audit value reported gaps in structured prose. Start there, then still decide " +
      "every listed criterion from the tree: a repair can regress a criterion an earlier audit passed, " +
      "and a narrow patch can open a new sibling gap."
  }
  return memoryBlock + scopeBlock
}
