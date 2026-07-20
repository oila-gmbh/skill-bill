package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

internal fun schemaError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)

// Strict decode of a keyed artifact map. Corrupt state loud-fails rather than being coerced to
// empty, which would otherwise turn it into a blind re-run / lost outputs on resume.
private fun <T> decodeStrictKeyedArtifactMap(
  artifacts: Map<String, Any?>,
  artifactKey: String,
  decodeEntry: (String, Map<String, Any?>) -> T,
): Map<String, T> {
  val raw = artifacts[artifactKey] ?: return emptyMap()
  val rawMap = raw as? Map<*, *>
    ?: schemaError("Feature-task-runtime artifact '$artifactKey' must decode to a map.")
  return rawMap.entries.associate { (key, value) ->
    val phaseId = key as? String
      ?: schemaError("Feature-task-runtime artifact '$artifactKey' must have string keys; found '$key'.")
    val entryMap = JsonSupport.anyToStringAnyMap(value)
      ?: schemaError("Feature-task-runtime artifact '$artifactKey' entry for '$phaseId' must decode to a map.")
    phaseId to decodeEntry(phaseId, entryMap)
  }
}

internal fun phaseRecordsFrom(artifacts: Map<String, Any?>): Map<String, FeatureTaskRuntimePhaseRecord> =
  decodeStrictKeyedArtifactMap(artifacts, FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY) { _, recordMap ->
    FeatureTaskRuntimePhaseRecord.fromArtifactMap(recordMap)
  }

internal fun phaseBriefingsFrom(artifacts: Map<String, Any?>): Map<String, FeatureTaskRuntimePhaseLaunchBriefing> =
  decodeStrictKeyedArtifactMap(artifacts, FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY) { _, briefingMap ->
    FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap(briefingMap)
  }

internal fun resolvedBranchFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeResolvedBranch? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY] ?: return null
  val entryMap = JsonSupport.anyToStringAnyMap(raw)
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY' must decode to a map.",
    )
  return FeatureTaskRuntimeResolvedBranch.fromArtifactMap(entryMap)
}

internal fun decomposeTerminalFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeDecomposeTerminal? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY] ?: return null
  val entryMap = JsonSupport.anyToStringAnyMap(raw)
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL_ARTIFACT_KEY' must decode to a map.",
    )
  return FeatureTaskRuntimeDecomposeTerminal.fromArtifactMap(entryMap)
}

internal fun phaseLedgerFrom(artifacts: Map<String, Any?>): List<FeatureTaskRuntimePhaseLedgerEntry> {
  val raw = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] ?: return emptyList()
  val rawList = raw as? List<*>
    ?: throw InvalidWorkflowStateSchemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY' must decode to a list.",
    )
  return rawList.map { item ->
    val entryMap = JsonSupport.anyToStringAnyMap(item)
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime phase ledger entry must decode to a string-keyed map.",
      )
    FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entryMap)
  }
}
