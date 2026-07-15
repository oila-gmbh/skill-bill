package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.RequiredArtifactPresenceResolver
import skillbill.workflow.model.ResolvedRequiredArtifact
import skillbill.workflow.model.WorkflowSnapshotView
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord

/**
 * Family-aware presence resolver for the feature-task-runtime pipeline. The runtime never
 * writes top-level per-phase artifact keys; its upstream outputs live in the private
 * per-phase records store under [FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY]. An upstream
 * phase id is therefore present iff its per-phase record exists with a `completed` status, so
 * the generic resume gate reads authoritative runtime state instead of a never-written key.
 *
 * Strict decode: a corrupt records map or entry loud-fails with [InvalidWorkflowStateSchemaError]
 * rather than coercing to empty, which would otherwise turn lost outputs into a blind re-run.
 * Pure domain function: no JDBC/HTTP/Files and no clock/random.
 */
object FeatureTaskRuntimeRequiredArtifactPresenceResolver : RequiredArtifactPresenceResolver {
  private const val PHASE_STATUS_COMPLETED = "completed"

  override fun missingRequiredArtifacts(
    snapshot: WorkflowSnapshotView,
    resumeStepId: String,
    requiredArtifacts: List<String>,
  ): List<String> {
    if (requiredArtifacts.isEmpty()) {
      return emptyList()
    }
    val completedPhaseIds = completedPhaseIds(snapshot)
    return requiredArtifacts.filterNot(completedPhaseIds::contains)
  }

  override fun resolveRequiredArtifact(
    snapshot: WorkflowSnapshotView,
    artifactKey: String,
  ): ResolvedRequiredArtifact {
    val record = decodePhaseRecords(snapshot)[artifactKey]
      ?: return ResolvedRequiredArtifact(present = false, value = null)
    if (record.status != PHASE_STATUS_COMPLETED) {
      return ResolvedRequiredArtifact(present = false, value = null)
    }
    return ResolvedRequiredArtifact(
      present = true,
      value = record.outputArtifact ?: record.toArtifactMap(),
    )
  }

  private fun completedPhaseIds(snapshot: WorkflowSnapshotView): Set<String> = decodePhaseRecords(snapshot)
    .filterValues { record -> record.status == PHASE_STATUS_COMPLETED }
    .keys

  private fun decodePhaseRecords(snapshot: WorkflowSnapshotView): Map<String, FeatureTaskRuntimePhaseRecord> {
    val raw = snapshot.artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY] ?: return emptyMap()
    val rawMap = raw as? Map<*, *>
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY' must decode to a map.",
      )
    return rawMap.entries.associate { (key, value) -> decodePhaseRecordEntry(key, value) }
  }

  private fun decodePhaseRecordEntry(key: Any?, value: Any?): Pair<String, FeatureTaskRuntimePhaseRecord> {
    val phaseId = key as? String
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY' must have string keys; " +
          "found '$key'.",
      )
    val entryMap = JsonSupport.anyToStringAnyMap(value)
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY' entry for " +
          "'$phaseId' must decode to a map.",
      )
    return phaseId to FeatureTaskRuntimePhaseRecord.fromArtifactMap(entryMap)
  }
}
