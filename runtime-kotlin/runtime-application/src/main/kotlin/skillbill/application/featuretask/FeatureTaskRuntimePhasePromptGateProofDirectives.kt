package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

private val GATE_PROOF_TOKENS: List<String> = listOf(
  "detekt",
  "scripts/validate",
  "bill-code-check",
  "./gradlew check",
  "gradlew check",
  "compilekotlin",
  "reports zero",
  "ktlint",
  "spotless",
  "maxissues",
  "collect_all",
  "collect-all",
)

private val BUILD_PROOF_TOKENS: List<String> = listOf(
  "compilekotlin",
  "build_command",
  "./gradlew compile",
  "gradlew compile",
  "compile/buildability",
  "compile proof",
)

fun acceptanceCriteriaRequireGateProof(criteria: List<String>): Boolean {
  val haystack = criteria.joinToString("\n").lowercase()
  return GATE_PROOF_TOKENS.any { it in haystack }
}

fun acceptanceCriteriaRequireBuildProof(criteria: List<String>): Boolean {
  val haystack = criteria.joinToString("\n").lowercase()
  return BUILD_PROOF_TOKENS.any { it in haystack }
}

fun isAuditGapImplement(briefing: FeatureTaskRuntimePhaseLaunchBriefing): Boolean =
  briefing.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
    (
      briefing.priorGapMemory != null ||
        briefing.handoffEnvelope.projections.any { it.projectionName == "audit_prose" }
      )

fun phaseEligibleForGateProofException(phaseId: String, auditGapImplement: Boolean): Boolean =
  phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT ||
    (
      phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
        auditGapImplement
      )

fun nonValidatePhaseValidationOwnershipDirective(
  phaseId: String,
  acceptanceCriteria: List<String>,
  auditGapImplement: Boolean,
): String {
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ||
    phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
  ) {
    return ""
  }
  val gateProofException =
    phaseEligibleForGateProofException(phaseId, auditGapImplement) &&
      acceptanceCriteriaRequireGateProof(acceptanceCriteria)
  if (gateProofException) {
    return """
      ## Validation ownership
      Acceptance criteria in this briefing require mechanical gate proof. For this phase only, you MAY
      run the commands those criteria name (for example detekt, the pack collect-all /
      bill-code-check, or `./gradlew check`) as evidence or remediation proof — and only those. On
      audit, put the COMPLETE remaining finding inventory for that proof into the gap note (total
      count plus rule/location identifiers; never a sample batch that leaves peers for a later
      round). On audit-gap implement, clear every finding from that inventory in this one
      invocation; re-run the same gate once at the end to confirm; do not defer peers to validate.
      Leave `tests_executed` empty. This exception does not replace the later validate phase, which
      still owns the full lifecycle gate.
    """.trimIndent()
  }
  return """
    ## Validation ownership
    Only the validate phase may run the pack validation gate
    (`validation_gate.collect_all_full_gate_command`), `./gradlew check`, `check ${"--"}continue`,
    `bill-code-check`, or any other full repository check suite. Only the build phase may run the
    pack build_command for compile/buildability proof. This phase must not compile, build,
    execute tests, or run check to prove the work. Ignore any Validation Strategy, plan note,
    acceptance text, review habit, or prior habit that asks you to run check here — that work waits
    for validate. If a receipt carries `tests_executed`, leave it empty.
  """.trimIndent()
}

fun nonBuildPhaseBuildOwnershipDirective(
  phaseId: String,
  acceptanceCriteria: List<String>,
  auditGapImplement: Boolean,
): String {
  if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) {
    return ""
  }
  val buildProofException =
    phaseEligibleForGateProofException(phaseId, auditGapImplement) &&
      acceptanceCriteriaRequireBuildProof(acceptanceCriteria)
  if (buildProofException) {
    return """
      ## Build ownership
      Acceptance criteria in this briefing require compile/buildability proof. For this phase only,
      you MAY run the pack build_command (`validation_gate.build_command`) or the compile command
      those criteria name — and only as evidence or remediation proof for those criteria. This does
      not replace the later build phase when the goal pipeline includes one.
    """.trimIndent()
  }
  return """
    ## Build ownership
    Only the build phase may run the pack build_command (`validation_gate.build_command`). This phase
    must not invoke compile-only proof as a substitute for its own work.
  """.trimIndent()
}
