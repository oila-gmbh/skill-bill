package skillbill.application.workflow
import skillbill.application.decomposition.DECOMPOSITION_RUNTIME_ARTIFACT_KEY
import skillbill.application.decomposition.asStringAnyMapOrNull
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.decomposition.decodeDecompositionManifestMap
import skillbill.error.LegacyProseWorkflowError
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.runtime.isActiveGoalRuntime
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.engine.model.WorkflowStateSnapshot

fun WorkflowStateSnapshot.decompositionRuntime(validator: DecompositionManifestValidator): DecompositionManifest? =
  decodeArtifacts(artifactsJson)[DECOMPOSITION_RUNTIME_ARTIFACT_KEY].asStringAnyMapOrNull()
    ?.let { decodeDecompositionManifestMap(it, validator, DECOMPOSITION_RUNTIME_ARTIFACT_KEY) }

fun WorkflowStateSnapshot.hasDecompositionPlan(): Boolean =
  decodeArtifacts(artifactsJson)["plan"].asStringAnyMapOrNull()?.get("mode") == "decompose"

val IMPLEMENT_TERMINAL_STATUSES: Set<String> = setOf("completed", "failed", "abandoned")

fun WorkflowStateRepository.listFeatureTaskWorkflowsForParentDiscovery(): List<WorkflowStateRecord> {
  val byId = LinkedHashMap<WorkflowId, WorkflowStateRecord>()
  listFeatureTaskWorkflows(FeatureTaskWorkflowMode.RUNTIME, Int.MAX_VALUE).forEach { row ->
    byId[row.workflowId] = row
  }
  listFeatureTaskWorkflows(FeatureTaskWorkflowMode.PROSE, Int.MAX_VALUE).forEach { row ->
    byId.putIfAbsent(row.workflowId, row)
  }
  return byId.values.toList()
}

fun WorkflowStateRecord.requireRuntimeModeForEngineWrite() {
  if (mode != FeatureTaskWorkflowMode.RUNTIME) {
    throw LegacyProseWorkflowError(workflowId, issueKey)
  }
}

fun WorkflowStateRepository.findDecomposedParentWorkflow(
  issueKey: IssueKey,
  validator: DecompositionManifestValidator,
  currentProjectedManifest: DecompositionManifest? = null,
): WorkflowStateRecord? {
  val normalizedIssueKey = issueKey.value.trim()
  val candidates = listFeatureTaskWorkflowsForParentDiscovery().mapNotNull { row ->
    val snapshot = row.toSnapshot()
    if (snapshot.isGoalContinuationChildWorkflow()) return@mapNotNull null
    val manifest = snapshot.decompositionRuntime(validator) ?: return@mapNotNull null
    if (
      (snapshot.hasDecompositionPlan() || row.issueKey?.value?.trim() == normalizedIssueKey) &&
      manifest.issueKey.value == normalizedIssueKey
    ) {
      DecomposedParentLookupCandidate(row, manifest)
    } else {
      null
    }
  }.filterNot { candidate -> candidate.isStaleAbandonedLineage(currentProjectedManifest) }
  val activeCandidates = candidates.filter { candidate -> candidate.manifest.isActiveGoalRuntime() }
  if (activeCandidates.size > 1) {
    error(
      "Ambiguous decomposed parent workflows for '$normalizedIssueKey': " +
        activeCandidates.joinToString { candidate -> candidate.record.workflowId.value } +
        ". Pass an explicit workflow or manifest selector before continuing.",
    )
  }
  return activeCandidates.firstOrNull()?.record
    ?: candidates.firstOrNull()?.record
}

private data class DecomposedParentLookupCandidate(
  val record: WorkflowStateRecord,
  val manifest: DecompositionManifest,
)

private fun DecomposedParentLookupCandidate.isStaleAbandonedLineage(
  currentProjectedManifest: DecompositionManifest?,
): Boolean {
  if (currentProjectedManifest == null || record.workflowStatus != "abandoned") return false
  if (manifest.subtasks.any { subtask -> subtask.hasStarted() }) return false
  return manifest.subtasks.map { it.specPath } != currentProjectedManifest.subtasks.map { it.specPath }
}

fun WorkflowStateSnapshot.isGoalContinuationChildWorkflow(): Boolean {
  val goalContinuation = decodeArtifacts(artifactsJson)["goal_continuation"].asStringAnyMapOrNull() ?: return false
  return goalContinuation["enabled"] == true ||
    goalContinuation.containsKey("issue_key") ||
    goalContinuation.containsKey("subtask_id")
}
