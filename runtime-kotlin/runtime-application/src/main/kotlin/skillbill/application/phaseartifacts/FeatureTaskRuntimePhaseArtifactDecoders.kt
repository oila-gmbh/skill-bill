package skillbill.application.phaseartifacts

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonCodec
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationFieldAdoption
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch

fun schemaError(detail: String): Nothing = throw InvalidWorkflowStateSchemaError(detail)

@OpenBoundaryMap("Strict keyed feature-task-runtime artifact map decode")
fun <T> decodeStrictKeyedArtifactMap(
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
    val entryMap = JsonCodec.anyToStringAnyMap(value)
      ?: schemaError("Feature-task-runtime artifact '$artifactKey' entry for '$phaseId' must decode to a map.")
    phaseId to decodeEntry(phaseId, entryMap)
  }
}

@OpenBoundaryMap("Feature-task-runtime phase records decode from durable workflow artifacts")
fun phaseRecordsFrom(artifacts: Map<String, Any?>): Map<String, FeatureTaskRuntimePhaseRecord> =
  decodeStrictKeyedArtifactMap(artifacts, FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY) { _, recordMap ->
    FeatureTaskRuntimePhaseRecord.fromArtifactMap(recordMap)
  }

@OpenBoundaryMap("Feature-task-runtime resolved branch decode from durable workflow artifacts")
fun resolvedBranchFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeResolvedBranch? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY] ?: return null
  val entryMap = JsonCodec.anyToStringAnyMap(raw)
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_RESOLVED_BRANCH_ARTIFACT_KEY' must decode to a map.",
    )
  return FeatureTaskRuntimeResolvedBranch.fromArtifactMap(entryMap)
}

@OpenBoundaryMap("Feature-task-runtime review generation decode from durable workflow artifacts")
fun reviewGenerationFrom(artifacts: Map<String, Any?>): Int {
  val raw = artifacts[FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY] ?: return 0
  val ordinal = when (raw) {
    is Int -> raw
    is Long -> raw.toInt()
    is Number -> if (raw.toDouble() == raw.toLong().toDouble()) raw.toInt() else null
    else -> null
  }
  return ordinal?.takeIf { it >= 0 }
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_REVIEW_GENERATION_ARTIFACT_KEY' must decode to a " +
        "non-negative integer; found '$raw'.",
    )
}

@OpenBoundaryMap("Feature-task-runtime operator block retry decode from durable workflow artifacts")
fun operatorBlockRetryFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeOperatorBlockRetry? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY] ?: return null
  val entryMap = JsonCodec.anyToStringAnyMap(raw)
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY' must decode to a map.",
    )
  return FeatureTaskRuntimeOperatorBlockRetry(
    phaseId = entryMap.requiredOperatorRetryString("phase_id"),
    reason = entryMap.requiredOperatorRetryString("reason"),
    retriedAt = entryMap.requiredOperatorRetryString("retried_at"),
  )
}

@OpenBoundaryMap("Feature-task-runtime goal continuation field adoption decode from durable workflow artifacts")
fun goalContinuationFieldAdoptionFrom(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationFieldAdoption? {
  val raw = artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY] ?: return null
  val entryMap = JsonCodec.anyToStringAnyMap(raw)
    ?: schemaError(
      "Feature-task-runtime artifact " +
        "'$FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY' must decode to a map.",
    )
  return FeatureTaskRuntimeGoalContinuationFieldAdoption.fromArtifactMap(entryMap)
}

private fun Map<String, Any?>.requiredOperatorRetryString(field: String): String =
  (this[field] as? String)?.takeIf(String::isNotBlank)
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY_ARTIFACT_KEY' field " +
        "'$field' must decode to a non-blank string.",
    )
