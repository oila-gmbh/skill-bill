package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.review.model.ReviewIssueCategory
import skillbill.workflow.goal.model.GoalSubtaskCommitFocusedAccounting
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS

fun outputContract(
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  agentRunValidateFallback: Boolean,
  settlement: FeatureTaskRuntimePhaseSettlementTarget? = null,
): String {
  val phaseId = briefing.phaseId
  val settlementSection = settlementDirective(phaseId, settlement)
  val heading = if (settlementSection.isEmpty()) {
    "## Required final output (validated schema gate)"
  } else {
    "## Fallback final output (validated schema gate; only when the settlement tools are unavailable)"
  }
  val envelopeSection = """
    $heading
    End your response with exactly one JSON object as the last thing you emit. Prefer a raw
    object with nothing after it; a single ```json fenced block is also accepted. The runtime
    extracts that object and blocks the run if it does not validate against the phase-output
    contract. Copy these field values from this briefing; do not look them up in this checkout:
    - "contract_version": must be exactly "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION"
    - "phase_id": must be "$phaseId"
    - "status": one of "completed", "blocked", "failed"
    - "failure_disposition": required by the runtime when status is "blocked" or "failed"; one of
      "retryable", "non_retryable_policy_conflict", "needs_user_action", "process_failure", or
      "invalid_output". Omit it when status is "completed".
    - "summary": non-empty string describing what this phase did
    - "produced_outputs": object with at least one entry carrying this phase's concrete
      result for downstream phases (for example plan steps, changed files, findings, or
      validation results)${producedOutputsAddendum(
    briefing,
    agentRunValidateFallback,
  )}
    - "derived_notes": optional; when present, a non-empty string of notes for downstream
      phases
    - "verdict": optional top-level string; verifying phases (review, audit) set it to drive the
      advance-vs-remediation decision — see the verifying-phase signal above
    No top-level fields other than the ones listed above are allowed.
  """.trimIndent()
  return if (settlementSection.isEmpty()) envelopeSection else settlementSection + "\n\n" + envelopeSection
}

private fun producedOutputsAddendum(
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  agentRunValidateFallback: Boolean,
): String {
  val phaseId = briefing.phaseId
  if (FeatureTaskRuntimePhaseWorkflowDefinition.isMutatingPhase(phaseId)) {
    return mutatingProducedOutputsAddendum(briefing, agentRunValidateFallback)
  }
  val findings = FeatureTaskRuntimeVerificationSignalKeys.REVIEW_FINDINGS
  val verdict = FeatureTaskRuntimeVerificationSignalKeys.VERDICT
  return when (phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
    -> FeatureTaskRuntimePhaseProjectionShapes.exampleFor(
      phaseId,
      agentRunValidateFallback,
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW ->
      "\n    - This is a VERIFYING phase: produced_outputs MUST carry a \"$findings\" array (each entry a\n" +
        "      severity/message object; an explicit empty [] affirms no Blocker or Major findings) AND/OR a\n" +
        "      top-level \"$verdict\" of \"approved\" or \"changes_requested\". A Blocker or Major finding sets\n" +
        "      \"changes_requested\" so it is fixed in this same review pass; Minor and Nit do not. Output\n" +
        "      carrying NEITHER signal fails the schema gate loudly — a prose summary alone cannot advance.\n" +
        "      Each finding's \"severity\" MUST be exactly one of blocker, major, minor, nit, and its\n" +
        "      \"issue_category\" MUST be exactly one of " +
        ReviewIssueCategory.entries.joinToString { it.wireValue } + "; any other category value is\n" +
        "      recorded as other.\n" +
        "    - produced_outputs MUST also carry \"${FeatureTaskRuntimeVerificationSignalKeys.REVIEW_RUN_ID}\": the " +
        "Review run ID your\n" +
        "      `bill-code-review` invocation reported for this pass, verbatim. It is the key that joins each\n" +
        "      finding here to the imported review run, so a finding's \"id\" plus this run id must be the same\n" +
        "      pair that review recorded. Omit it ONLY if the review genuinely reported no run id; never\n" +
        "      invent, reuse an older, or guess one." + commitFocusedAccountingAddendum()
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS ->
      "\n    - This is a VERIFYING phase: emit top-level \"$verdict\" as \"findings_verified\" or " +
        "\"no_findings_verified\" and exactly one " +
        "produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS} " +
        "entry per review finding with required census fields finding_id and disposition (verified " +
        "or rejected). Recommended optional fields per entry: reason, severity, location, message, " +
        "selected_boundary_headings (heading_id and source_path), and boundary_context_unavailable " +
        "when no eligible boundary owns the finding paths. When you cite boundary memory, copy " +
        "heading_id and source_path verbatim from that finding's boundary_catalog only — never " +
        "invent hashes or reuse another finding's catalog. Concurrent worktree dirt outside the " +
        "review scope is ignored; settle dispositions for the reviewed findings only.\n" +
        "      Required example: {\"finding_id\":\"F-001\",\"disposition\":\"verified\"}."
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> auditProducedOutputsAddendum(
      verdict = verdict,
      briefing = briefing,
    )
    else -> ""
  }
}

private fun mutatingProducedOutputsAddendum(
  briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  agentRunValidateFallback: Boolean,
): String {
  val phaseId = briefing.phaseId
  val remediation = if (!briefing.handoffEnvelope.projections.any { it.projectionName == "audit_prose" }) {
    ""
  } else if (acceptanceCriteriaRequireGateProof(briefing.acceptanceCriteria)) {
    "\n    - This is AUDIT-GAP REMEDIATION with gate-proof acceptance criteria: read the audit_prose " +
      "value as the complete finding inventory for the unmet criterion. Clear every finding from that " +
      "inventory in this one invocation — never a sample batch, never defer peers to validate. Run " +
      "only the gate commands Validation ownership allows; re-run that same gate once at the end to " +
      "confirm. Respect blast radius so the repair does not open a new gap or regress a neighboring " +
      "criterion. Then emit a non-blank value string carrying the updated implementation_receipt JSON " +
      "stuffed inside value. If a criterion is genuinely unimplementable, leave through a blocked " +
      "envelope naming it and why."
  } else {
    "\n    - This is AUDIT-GAP REMEDIATION: read the audit_prose value from the briefing as structured " +
      "prose (gap report stuffed inside value). Follow every gap named there completely in this one " +
      "invocation: execute the planned production change, respect blast radius, and check surrounding " +
      "callers/contracts so the repair does not open a new gap or regress a neighboring criterion. Do " +
      "not invent a narrower substitute plan. Then emit a non-blank value string carrying the updated " +
      "implementation_receipt JSON stuffed inside value. If a criterion is genuinely unimplementable, " +
      "leave through a blocked envelope naming it and why."
  }
  val reconciliationRequirement =
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT) {
      ""
    } else {
      "\n    - produced_outputs MUST include a reconciliation report: a \"reconciled_state\" object\n" +
        "      (or a \"reconciled_state\" entry) with \"reconciled\": true and concrete evidence that the\n" +
        "      changed files are at their intended target state. A status of \"completed\" with the\n" +
        "      reconciliation report missing or \"reconciled\" not true fails the schema gate loudly."
    }
  return reconciliationRequirement +
    FeatureTaskRuntimePhaseProjectionShapes.exampleFor(
      phaseId,
      agentRunValidateFallback,
    ) + remediation
}

private fun commitFocusedAccountingAddendum(): String =
  "\n    - If this pass ran a DELEGATED review over a real commit sequence, produced_outputs MUST also\n" +
    "      carry \"commit_focused_accounting\" exactly as the review reported it: commit_sequence_digest\n" +
    "      (64-char lowercase hex), commit_count, lane_count, focused_commit_count,\n" +
    "      skipped_commit_count (focused + skipped == commit_count), and integration_terminal_outcome,\n" +
    "      one of " + GoalSubtaskCommitFocusedAccounting.INTEGRATION_TERMINAL_OUTCOMES.sorted()
      .joinToString() + ".\n" +
    "      Optional when the review reported them: routing_digest, focused_pair_count,\n" +
    "      skipped_pair_count, lane_bundle_sizes, lane_segment_counts, incomplete_lanes,\n" +
    "      parent_analysis_pairs, parent_analysis_bytes, integration_finding_count, and\n" +
    "      integration_skip_reason (REQUIRED when integration_terminal_outcome is\n" +
    "      ${GoalSubtaskCommitFocusedAccounting.SKIPPED_NOT_APPLICABLE}). Lanes that ended incomplete\n" +
    "      are named in incomplete_lanes; that is non-clean coverage and the integration pass never\n" +
    "      compensates for it. Identities, counts, and lane names ONLY — never a commit subject, a\n" +
    "      path, or diff text. An INLINE or non-commit-sequence pass OMITS the key entirely rather\n" +
    "      than fabricating a sequence identity; never invent or guess a digest or a count."

private fun auditProducedOutputsAddendum(verdict: String, briefing: FeatureTaskRuntimePhaseLaunchBriefing): String =
  "\n    - This is a VERIFYING phase. Ignore the optional-verdict bullet above: for audit, top-level " +
    "\"$verdict\" is REQUIRED. Copy exactly one token: satisfied | gaps_found.\n" +
    "      REJECTED: omitting verdict; any other string; nesting verdict only inside produced_outputs.\n" +
    "      ACCEPTED root: {\"contract_version\":\"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION\"," +
    "\"phase_id\":\"audit\",\"status\":\"completed\",\"verdict\":\"satisfied\"," +
    "\"summary\":\"<one sentence>\"," +
    "\"produced_outputs\":{\"value\":\"{\\\"gaps\\\":[],\\\"non_blocking_findings\\\":[]}\"}}.\n" +
    "      Emit a non-blank produced_outputs.value string. For satisfied, value may affirm every " +
    "criterion is met (for example {\"gaps\":[],\"non_blocking_findings\":[]}). For gaps_found, " +
    "stuff one entry per unmet criterion inside value, for example " +
    "{\"gaps\":[{\"criterion\":\"AC-003\",\"note\":\"...\"}],\"non_blocking_findings\":[]}.\n" +
    "      Recommended inner shape uses criterion plus note — free-form note prose; no required " +
    "keywords or sections. The runtime does not block on note length, extra keys, or other inner " +
    "schema polish; it reads only the envelope verdict to decide advance versus audit_gap re-entry.\n" +
    "      criterion is AC-###. note is one dense line of at most " +
    "$FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS characters. Prefer notes that both name what is\n" +
    "      missing and give implement enough of a fix plan to close the gap carefully: the intended\n" +
    "      production change, blast radius on callers/DI/sibling phases/contracts, and how to avoid\n" +
    "      regressing neighbors or opening a new gap. Prefer a complete correct plan over a narrow\n" +
    "      patch. Never a diff hunk, a source body, or a line number.\n" +
    "      Legacy sibling keys beside value (gaps, unmet_criteria, audit_repair_plan, and similar) " +
    "are ignored; put planning guidance in value only.\n" +
    auditNoEarlierAuditLine(briefing) +
    "      Minor and nit findings belong only inside value under non_blocking_findings and they\n" +
    "      NEVER trigger gaps_found by themselves: severity (minor or nit) is required, " +
    "acceptance_criterion_ref and\n" +
    "      message are expected. Example: {\"acceptance_criterion_ref\":\"AC-004\",\n" +
    "       \"message\":\"Naming could be clearer\",\"severity\":\"nit\"}.\n" +
    "      TEST EXCLUSION: missing tests, weak tests, incomplete test coverage, unrealistic fixtures,\n" +
    "      insufficient assertions, and any other test-only concern are NEVER unmet criteria. Do not\n" +
    "      inspect or assess test adequacy and do not cite test files. Validation owns test execution\n" +
    "      and failures. Report only a concrete defect in production behavior or production\n" +
    "      implementation; when no such defect is evidenced, emit satisfied even if test coverage is\n" +
    "      absent or inadequate." +
    auditRoundScopeAddendum(briefing)
