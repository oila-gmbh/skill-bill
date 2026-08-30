package skillbill.application.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.canonicalAcceptanceCriterionRef

/**
 * The resolved checkpoint the envelope was validated against (AC-011). A consumer that must compare
 * producer claims to the tree — audit above all — needs the exact scope, not just the claims. The
 * section renders in the framing pass too, so an oversized owned-path inventory hits the framing
 * ceiling and loud-fails instead of being silently trimmed.
 */
internal fun StringBuilder.appendRepositoryCheckpoint(
  handoff: FeatureTaskRuntimePhaseHandoff,
  envelope: FeatureTaskRuntimeHandoffEnvelope,
) {
  val requiresCheckpoint = handoff.projectionDeclarations.any { declaration ->
    declaration.checkpointPolicy != FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED
  }
  val checkpoint = envelope.repositoryCheckpoint?.takeIf { requiresCheckpoint } ?: return
  appendLine("## Repository checkpoint (layer 2, resolved)")
  appendLine("fingerprint: ${escapeBriefingLineBreaks(checkpoint.fingerprint)}")
  checkpoint.baseRef?.let { appendLine("base_ref: ${escapeBriefingLineBreaks(it)}") }
  checkpoint.headRef?.let { appendLine("head_ref: ${escapeBriefingLineBreaks(it)}") }
  appendLine("scoped_owned_paths:")
  if (checkpoint.workingTreeOwnedPaths.isEmpty()) {
    appendLine("  (none)")
  } else {
    // The inventory comes from `-z` plumbing, which disables C-quoting, so a filename may legally
    // carry a newline. Unescaped, such a path would open its own briefing section.
    checkpoint.workingTreeOwnedPaths.forEach { path -> appendLine("  - ${escapeBriefingLineBreaks(path)}") }
  }
  appendLine()
}

// Only prompt-visible projections render; a private-evidence-only projection stays durable state.
internal fun StringBuilder.appendProjections(envelope: FeatureTaskRuntimeHandoffEnvelope) {
  val visible = envelope.promptVisibleProjections
  if (visible.isEmpty()) {
    appendLine("(none)")
    return
  }
  visible.forEach { projection ->
    append(projection.canonicalDeliveredRendering)
  }
}

/**
 * Keeps one producer-supplied value on one briefing line.
 *
 * The briefing is a line-structured document whose section headers ("## Run invariants", "##
 * Repository checkpoint") are what a consumer trusts to tell run-owned context from producer claims.
 * An unescaped line break inside a projection string would let a producer emit its own header and
 * forge that context for the next phase — a real escalation on commit_push and pr, where the run
 * invariants section is the sole delivery path for operator mandates. Escaping keeps the content
 * intact and readable while making it structurally incapable of opening a new section.
 */
internal fun escapeBriefingLineBreaks(value: String): String =
  value.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

// Layer 1 rendering is allowlist-driven per phase; the typed fields stay on the briefing regardless.
internal fun StringBuilder.appendAllowlistedRunInvariants(handoff: FeatureTaskRuntimePhaseHandoff) {
  val invariants = handoff.runInvariants
  val allowlist = FeatureTaskRuntimeRunInvariantPromptAllowlist.forPhase(handoff.phaseId)
  appendLine("## Run invariants (layer 1, unconditional)")
  if (FeatureTaskRuntimeRunInvariantPromptField.SPEC_REFERENCE in allowlist) {
    appendLine("spec_reference: ${invariants.specReference}")
  }
  if (FeatureTaskRuntimeRunInvariantPromptField.FEATURE_SIZE in allowlist) {
    appendLine("feature_size: ${invariants.featureSize.name}")
  }
  if (FeatureTaskRuntimeRunInvariantPromptField.CEREMONY_SCALING in allowlist) {
    appendLine("ceremony_scaling:")
    FeatureTaskRuntimePhaseWorkflowQueries.ceremonyScaling(invariants.featureSize)
      .toBriefingLines()
      .forEach { line -> appendLine("  $line") }
  }
  if (FeatureTaskRuntimeRunInvariantPromptField.ACCEPTANCE_CRITERIA in allowlist) {
    appendAcceptanceCriteria(handoff)
  }
  if (FeatureTaskRuntimeRunInvariantPromptField.MANDATES_AND_OVERRIDES in allowlist) {
    appendLine("mandates_and_overrides:")
    if (invariants.mandatesAndOverrides.isEmpty()) {
      appendLine("  (none)")
    } else {
      invariants.mandatesAndOverrides.forEach { mandate -> appendLine("  - $mandate") }
    }
  }
}

internal fun StringBuilder.appendAcceptanceCriteria(handoff: FeatureTaskRuntimePhaseHandoff) {
  appendLine("acceptance_criteria:")
  val closedCriterionRefs = handoff.durablyClosedCriterionRefs.toSet()
  handoff.runInvariants.acceptanceCriteria.forEachIndexed { index, criterion ->
    val criterionRef = canonicalAcceptanceCriterionRef(index + 1)
    if (criterionRef !in closedCriterionRefs) appendLine("  $criterionRef. $criterion")
  }
  if (closedCriterionRefs.isNotEmpty()) {
    appendLine("durably_closed_criteria:")
    appendLine("  (each reached a satisfied verdict and is closed; do not re-verify or report a gap against it)")
    closedCriterionRefs.sorted().forEach { criterionRef -> appendLine("  - $criterionRef") }
  }
}

private const val SHARED_EVIDENCE_PROJECTION: String =
  FeatureTaskRuntimePhaseWorkflowDefinition.SHARED_REVIEW_EVIDENCE_PROJECTION_NAME

private const val SELF_READ_DIFF_INSTRUCTION: String =
  "read the branch diff yourself; it is not delivered in this briefing"

private const val SHARED_EVIDENCE_DIFF_INSTRUCTION: String =
  "the branch diff is already derived for you: the '$SHARED_EVIDENCE_PROJECTION' projection above " +
    "carries its store_path, checkpoint_fingerprint, base_ref/head_ref, and per-file hunk index; " +
    "work from that reference, and dereference store_path when you need the diff bytes themselves"

private const val SHARED_EVIDENCE_UNIT_INSTRUCTION: String =
  "the current unit of work is already derived for you: the '$SHARED_EVIDENCE_PROJECTION' projection " +
    "above carries its store_path, checkpoint_fingerprint, base_ref/head_ref, and per-file hunk index; " +
    "work from that reference, and dereference store_path when you need the diff bytes themselves"

private const val SELF_READ_UNIT_INSTRUCTION: String =
  "read the current unit of work yourself; the shared evidence projection is not delivered in this briefing"

private fun derivedContextInstruction(key: String, sharedEvidenceDelivered: Boolean): String? = when (key) {
  FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_DIFF ->
    if (sharedEvidenceDelivered) SHARED_EVIDENCE_DIFF_INSTRUCTION else SELF_READ_DIFF_INSTRUCTION
  "current_unit_of_work" ->
    if (sharedEvidenceDelivered) SHARED_EVIDENCE_UNIT_INSTRUCTION else SELF_READ_UNIT_INSTRUCTION
  FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE ->
    "read the repository at the resolved checkpoint above — the diff over base_ref/head_ref plus " +
      "the listed scoped_owned_paths — and treat that actual state, not any upstream receipt claim, " +
      "as the evidence for every criterion"
  FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF ->
    SELF_READ_DIFF_INSTRUCTION
  else -> null
}

internal fun renderFeatureTaskRuntimePhaseBriefing(
  handoff: FeatureTaskRuntimePhaseHandoff,
  envelope: FeatureTaskRuntimeHandoffEnvelope,
): String = buildString {
  appendLine("# Feature-task-runtime phase briefing")
  appendLine("phase: ${handoff.phaseId}")
  handoff.drivingVerdict?.let { verdict -> appendLine("driving_verdict: ${verdict.wireValue}") }
  appendLine()
  appendAllowlistedRunInvariants(handoff)
  appendLine()
  appendLine("## Upstream projections (layer 2, declared and validated)")
  appendProjections(envelope)
  appendLine()
  appendRepositoryCheckpoint(handoff, envelope)
  appendLine("## Derived context (layer 3, declared)")
  if (handoff.derivedContextKeys.isEmpty()) {
    append("(none)")
  } else {
    val sharedEvidenceDelivered = envelope.projections.any { it.projectionName == SHARED_EVIDENCE_PROJECTION }
    append(
      handoff.derivedContextKeys.joinToString(separator = "\n") { key ->
        derivedContextInstruction(key, sharedEvidenceDelivered)
          ?.let { instruction -> "- $key: $instruction" }
          ?: "- $key"
      },
    )
  }
}
