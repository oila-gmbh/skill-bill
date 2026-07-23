package skillbill.application.workflow

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.asStringAnyMapOrNull
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.decodeDecompositionManifestMap
import skillbill.application.decomposition.parentSpecPath
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.workflow.DecompositionManifestValidator
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot

internal fun WorkflowStateSnapshot.decompositionRuntime(
  validator: DecompositionManifestValidator,
): DecompositionManifest? = decodeArtifacts(artifactsJson)[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
  ?.let { decodeDecompositionManifestMap(it, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }

internal fun WorkflowStateSnapshot.hasDecompositionPlan(): Boolean =
  decodeArtifacts(artifactsJson)["plan"].asStringAnyMapOrNull()?.get("mode") == "decompose"

internal fun WorkflowStateRepository.findDecomposedParentWorkflow(
  issueKey: String,
  validator: DecompositionManifestValidator,
  currentProjectedManifest: DecompositionManifest? = null,
): WorkflowStateRecord? {
  val normalizedIssueKey = issueKey.trim()
  val candidates = listFeatureImplementWorkflows(Int.MAX_VALUE).mapNotNull { row ->
    val snapshot = row.toSnapshot()
    if (snapshot.isGoalContinuationChildWorkflow()) return@mapNotNull null
    val manifest = snapshot.decompositionRuntime(validator) ?: return@mapNotNull null
    if (snapshot.hasDecompositionPlan() && manifest.issueKey == normalizedIssueKey) {
      DecomposedParentCandidate(row, manifest)
    } else {
      null
    }
  }.filterNot { candidate -> candidate.isStaleAbandonedLineage(currentProjectedManifest) }
  val activeCandidates = candidates.filter { candidate -> candidate.manifest.isActiveGoalRuntime() }
  if (activeCandidates.size > 1) {
    error(
      "Ambiguous decomposed parent workflows for '$normalizedIssueKey': " +
        activeCandidates.joinToString { candidate -> candidate.record.workflowId } +
        ". Pass an explicit workflow or manifest selector before continuing.",
    )
  }
  return activeCandidates.firstOrNull()?.record
    ?: candidates.firstOrNull()?.record
}

internal fun WorkflowStateRepository.findDecomposedParentWorkflowForRuntime(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
): WorkflowStateRecord? = listFeatureImplementWorkflows(Int.MAX_VALUE).firstOrNull { row ->
  val snapshot = row.toSnapshot()
  snapshot.hasDecompositionPlan() && snapshot.decompositionRuntime(validator)?.sameRuntimeIdentity(manifest) == true
}

private fun DecompositionManifest.sameRuntimeIdentity(other: DecompositionManifest): Boolean =
  issueKey == other.issueKey &&
    parentSpecPath == other.parentSpecPath &&
    subtasks.map { it.specPath } == other.subtasks.map { it.specPath }

private data class DecomposedParentCandidate(
  val record: WorkflowStateRecord,
  val manifest: DecompositionManifest,
)

// An abandoned parent row whose stored subtask lineage no longer matches the current governed
// manifest on disk, and which never recorded any real subtask progress, is dead bookkeeping from a
// superseded manifest edit (e.g. a subtask inserted/renumbered after the row was created). It must
// not shadow the current manifest for a fresh continuation lookup. A row still carrying real
// progress (any subtask `hasStarted()`) is never treated as stale here, regardless of workflow
// status, so genuine history is never discarded.
private fun DecomposedParentCandidate.isStaleAbandonedLineage(
  currentProjectedManifest: DecompositionManifest?,
): Boolean {
  if (currentProjectedManifest == null || record.workflowStatus != "abandoned") return false
  if (manifest.subtasks.any { subtask -> subtask.hasStarted() }) return false
  return manifest.subtasks.map { it.specPath } != currentProjectedManifest.subtasks.map { it.specPath }
}

internal fun DecompositionManifest.isActiveGoalRuntime(): Boolean = status !in setOf("complete", "skipped") &&
  subtasks.any { subtask -> subtask.status !in setOf("complete", "skipped") }

private fun WorkflowStateSnapshot.isGoalContinuationChildWorkflow(): Boolean {
  val goalContinuation = decodeArtifacts(artifactsJson)["goal_continuation"].asStringAnyMapOrNull() ?: return false
  return goalContinuation["enabled"] == true ||
    goalContinuation.containsKey("issue_key") ||
    goalContinuation.containsKey("subtask_id")
}
