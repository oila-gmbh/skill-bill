package skillbill.application

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
): WorkflowStateRecord? {
  val normalizedIssueKey = issueKey.trim()
  val candidates = listFeatureImplementWorkflows(Int.MAX_VALUE).mapNotNull { row ->
    val snapshot = row.toSnapshot()
    val manifest = snapshot.decompositionRuntime(validator) ?: return@mapNotNull null
    if (snapshot.hasDecompositionPlan() && manifest.issueKey == normalizedIssueKey) {
      DecomposedParentCandidate(row, manifest)
    } else {
      null
    }
  }
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

internal fun DecompositionManifest.isActiveGoalRuntime(): Boolean = status !in setOf("complete", "skipped") &&
  subtasks.any { subtask -> subtask.status !in setOf("complete", "skipped") }
