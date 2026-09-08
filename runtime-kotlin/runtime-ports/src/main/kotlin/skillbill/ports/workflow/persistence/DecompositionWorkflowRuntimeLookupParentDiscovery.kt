package skillbill.ports.workflow.persistence
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.runtime.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.ports.workflow.decomposition.runtime.decodeArtifacts
import skillbill.ports.workflow.decomposition.runtime.isActiveGoalRuntime
import skillbill.ports.workflow.model.FeatureTaskRuntimeSnapshot
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.WorkflowStateSnapshot

fun WorkflowStateRepository.findDecomposedParentOrCorruptFallback(
  issueKey: String,
  validator: DecompositionManifestValidator,
  currentProjectedManifest: DecompositionManifest?,
): WorkflowStateRecord? {
  val normalizedIssueKey = issueKey.trim()
  val discovered = listFeatureTaskWorkflowsForParentDiscovery().mapNotNull { candidate ->
    findParentDiscoveryResult(candidate, normalizedIssueKey, validator)
  }
  val validCandidates = discovered.mapNotNull { it.validCandidate }
  val corruptCandidates = discovered.mapNotNull { it.corruptRecord }
  val nonStale = validCandidates.filterNot { it.isStaleAbandonedLineage(currentProjectedManifest) }
  val active = nonStale.filter { it.manifest.isActiveGoalRuntime() }
  if (active.size > 1) {
    error(
      "Ambiguous decomposed parent workflows for '$normalizedIssueKey': " +
        active.joinToString { it.record.workflowId } +
        ". Pass an explicit workflow or manifest selector before continuing.",
    )
  }
  val validRecord = (active.firstOrNull() ?: nonStale.firstOrNull())?.record
  if (validRecord != null) return validRecord
  if (corruptCandidates.size > 1) {
    error(
      "Ambiguous corrupt-manifest parent rows for '$normalizedIssueKey': " +
        corruptCandidates.joinToString { it.workflowId } +
        ". Operator intervention is required to resolve the duplicate parent rows.",
    )
  }
  return corruptCandidates.firstOrNull()
}

private fun findParentDiscoveryResult(
  candidate: FeatureTaskRuntimeSnapshot,
  normalizedIssueKey: String,
  validator: DecompositionManifestValidator,
): ParentDiscoveryResult? {
  val row = candidate.workflow
  return try {
    val snapshot = row.toSnapshot()
    val isParent = snapshot.isDecomposedParentFor(row, normalizedIssueKey)
    val manifest = if (isParent) snapshot.decompositionRuntime(validator) else null
    when {
      !isParent || row.workflowStatus in IMPLEMENT_TERMINAL_STATUSES -> null
      manifest == null -> ParentDiscoveryResult(corruptRecord = row)
      manifest.issueKey != normalizedIssueKey -> null
      else -> ParentDiscoveryResult(validCandidate = DecomposedParentCandidate(row, manifest))
    }
  } catch (error: InvalidWorkflowStateSchemaError) {
    if (candidate.ownershipFor(normalizedIssueKey) == FeatureTaskRuntimeSnapshotOwnership.EXPLICIT_MISMATCH) {
      null
    } else {
      throw error
    }
  }
}

private fun WorkflowStateSnapshot.isDecomposedParentFor(
  row: WorkflowStateRecord,
  normalizedIssueKey: String,
): Boolean {
  if (isGoalContinuationChildWorkflow()) return false
  if (row.issueKey != normalizedIssueKey) return false
  return hasDecompositionPlan() || DECOMPOSITION_RUNTIME_ARTIFACT_KEY in decodeArtifacts(artifactsJson)
}

private data class ParentDiscoveryResult(
  val validCandidate: DecomposedParentCandidate? = null,
  val corruptRecord: WorkflowStateRecord? = null,
)

private data class DecomposedParentCandidate(
  val record: WorkflowStateRecord,
  val manifest: DecompositionManifest,
)

private fun DecomposedParentCandidate.isStaleAbandonedLineage(
  currentProjectedManifest: DecompositionManifest?,
): Boolean {
  if (currentProjectedManifest == null || record.workflowStatus != "abandoned") return false
  if (manifest.subtasks.any { subtask -> subtask.hasStarted() }) return false
  return manifest.subtasks.map { it.specPath } != currentProjectedManifest.subtasks.map { it.specPath }
}

private fun DecompositionManifest.sameRuntimeIdentity(other: DecompositionManifest): Boolean =
  issueKey == other.issueKey &&
    parentSpecPath == other.parentSpecPath &&
    subtasks.map { it.specPath } == other.subtasks.map { it.specPath }

fun WorkflowStateRepository.findDecomposedParentWorkflowForRuntime(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
): WorkflowStateRecord? = listFeatureTaskRuntimeSnapshots(Int.MAX_VALUE).firstNotNullOfOrNull { candidate ->
  val row = candidate.workflow
  val ownership = candidate.ownershipFor(manifest.issueKey)
  try {
    val snapshot = row.toSnapshot()
    row.takeIf {
      !snapshot.isGoalContinuationChildWorkflow() &&
        (snapshot.hasDecompositionPlan() || row.issueKey?.trim() == manifest.issueKey) &&
        snapshot.decompositionRuntime(validator)?.sameRuntimeIdentity(manifest) == true
    }
  } catch (error: InvalidWorkflowStateSchemaError) {
    if (ownership != FeatureTaskRuntimeSnapshotOwnership.EXPLICIT_MISMATCH) throw error
    null
  }
}
