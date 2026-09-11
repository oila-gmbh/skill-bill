package skillbill.engine.featuretask

import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowQueries
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffEnvelope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseHandoff
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.canonicalAcceptanceCriterionRef
import java.security.MessageDigest

fun StringBuilder.appendRepositoryCheckpoint(
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
  appendLine("scoped_owned_path_count: ${checkpoint.workingTreeOwnedPaths.size}")
  appendLine("scoped_owned_path_digest: ${scopedOwnedPathDigest(checkpoint.workingTreeOwnedPaths)}")
  appendLine()
}

fun StringBuilder.appendProjections(envelope: FeatureTaskRuntimeHandoffEnvelope) {
  val visible = envelope.promptVisibleProjections
  if (visible.isEmpty()) {
    appendLine("(none)")
    return
  }
  visible.forEach { projection ->
    append(projection.canonicalDeliveredRendering)
  }
}

private fun scopedOwnedPathDigest(paths: List<String>): String {
  val digest = MessageDigest.getInstance("SHA-256")
  paths.sorted().forEach { path ->
    digest.update(path.toByteArray())
    digest.update(0)
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun escapeBriefingLineBreaks(value: String): String =
  value.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

fun StringBuilder.appendAllowlistedRunInvariants(handoff: FeatureTaskRuntimePhaseHandoff) {
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

fun StringBuilder.appendAcceptanceCriteria(handoff: FeatureTaskRuntimePhaseHandoff) {
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
    "carries its store_path, checkpoint_fingerprint, base_ref/head_ref, and index measurements; " +
    "dereference store_path for the file list and the diff bytes themselves"

private const val SHARED_EVIDENCE_UNIT_INSTRUCTION: String =
  "the current unit of work is already derived for you: the '$SHARED_EVIDENCE_PROJECTION' projection " +
    "above carries its store_path, checkpoint_fingerprint, base_ref/head_ref, and index measurements; " +
    "dereference store_path for the file list and the diff bytes themselves"

private const val SELF_READ_UNIT_INSTRUCTION: String =
  "read the current unit of work yourself; the shared evidence projection is not delivered in this briefing"

internal const val SCOPED_REPOSITORY_STATE_INSTRUCTION: String =
  "The checkpoint includes current working-tree contents: staged, unstaged, and untracked changes. " +
    "head_ref is the last committed revision and may predate the implementation being audited. " +
    "Discover the changed paths yourself with git status --porcelain and " +
    "git diff --name-status <base_ref>, then read those current files, including deletions; " +
    "scoped_owned_path_count and scoped_owned_path_digest identify the inventory the runtime " +
    "resolved without enumerating it here. Without base_ref, inspect the current files and their " +
    "changes from head_ref. git show <head_ref>:<path> alone is not current-state evidence. " +
    "Judge criteria against these current files, not upstream receipt claims or an older commit."

private fun derivedContextInstruction(key: String, sharedEvidenceDelivered: Boolean): String? = when (key) {
  FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_DIFF ->
    if (sharedEvidenceDelivered) SHARED_EVIDENCE_DIFF_INSTRUCTION else SELF_READ_DIFF_INSTRUCTION
  "current_unit_of_work" ->
    if (sharedEvidenceDelivered) SHARED_EVIDENCE_UNIT_INSTRUCTION else SELF_READ_UNIT_INSTRUCTION
  FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE ->
    SCOPED_REPOSITORY_STATE_INSTRUCTION
  FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF ->
    SELF_READ_DIFF_INSTRUCTION
  else -> null
}

fun renderFeatureTaskRuntimePhaseBriefing(
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
