package skillbill.application

import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.WorkflowStateSnapshot

internal fun WorkflowStateSnapshot.decompositionRuntime(): DecompositionManifest? =
  decodeArtifacts(artifactsJson)[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
    ?.let { decodeDecompositionManifestMap(it, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }

internal fun WorkflowStateSnapshot.hasDecompositionPlan(): Boolean =
  decodeArtifacts(artifactsJson)["plan"].asStringAnyMapOrNull()?.get("mode") == "decompose"

internal fun WorkflowStateRepository.findDecomposedParentWorkflow(issueKey: String): WorkflowStateRecord? {
  val normalizedIssueKey = issueKey.trim()
  return listFeatureImplementWorkflows(Int.MAX_VALUE).firstOrNull { row ->
    val snapshot = row.toSnapshot()
    snapshot.hasDecompositionPlan() && snapshot.decompositionRuntime()?.issueKey == normalizedIssueKey
  }
}

internal fun WorkflowStateRepository.findDecomposedParentWorkflowForRuntime(
  manifest: DecompositionManifest,
): WorkflowStateRecord? = listFeatureImplementWorkflows(Int.MAX_VALUE).firstOrNull { row ->
  val snapshot = row.toSnapshot()
  snapshot.hasDecompositionPlan() && snapshot.decompositionRuntime()?.sameRuntimeIdentity(manifest) == true
}

private fun DecompositionManifest.sameRuntimeIdentity(other: DecompositionManifest): Boolean =
  issueKey == other.issueKey &&
    parentSpecPath == other.parentSpecPath &&
    subtasks.map { it.specPath } == other.subtasks.map { it.specPath }
