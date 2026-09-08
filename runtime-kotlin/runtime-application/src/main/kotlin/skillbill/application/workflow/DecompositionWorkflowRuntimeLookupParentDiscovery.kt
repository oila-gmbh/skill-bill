package skillbill.application.workflow

import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.decodeArtifacts
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.isActiveGoalRuntime
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.workflowStatus

fun WorkflowStateRepository.findDecomposedParentOrCorruptFallback(
  issueKey: String,
  validator: DecompositionManifestValidator,
  currentProjectedManifest: DecompositionManifest?,
): WorkflowStateRecord? {
  val normalizedIssueKey = issueKey.trim()
  val validCandidates = mutableListOf<DecomposedParentCandidate>()
  val corruptCandidates = mutableListOf<WorkflowStateRecord>()
  listFeatureTaskWorkflowsForParentDiscovery()
    .filter { row ->
      val snapshot = row.toSnapshot()
      !snapshot.isGoalContinuationChildWorkflow() &&
        row.issueKey == normalizedIssueKey &&
        (
          snapshot.hasDecompositionPlan() ||
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY in decodeArtifacts(snapshot.artifactsJson)
          )
    }
    .forEach { row ->
      val manifest = row.toSnapshot().decompositionRuntime(validator)
      val workflowStatus = row.workflowStatus.workflowStatus()
      when {
        manifest != null &&
          manifest.issueKey == normalizedIssueKey &&
          workflowStatus !in IMPLEMENT_TERMINAL_STATUSES ->
          validCandidates += DecomposedParentCandidate(row, manifest)
        manifest == null && workflowStatus !in IMPLEMENT_TERMINAL_STATUSES ->
          corruptCandidates += row
      }
    }
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

private data class DecomposedParentCandidate(
  val record: WorkflowStateRecord,
  val manifest: DecompositionManifest,
)

private fun DecomposedParentCandidate.isStaleAbandonedLineage(
  currentProjectedManifest: DecompositionManifest?,
): Boolean {
  if (currentProjectedManifest == null || record.workflowStatus.workflowStatus() != WorkflowStatus.ABANDONED) return false
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
): WorkflowStateRecord? = listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, Int.MAX_VALUE).firstOrNull { row ->
  val snapshot = row.toSnapshot()
  !snapshot.isGoalContinuationChildWorkflow() &&
    (snapshot.hasDecompositionPlan() || row.issueKey?.trim() == manifest.issueKey) &&
    snapshot.decompositionRuntime(validator)?.sameRuntimeIdentity(manifest) == true
}
