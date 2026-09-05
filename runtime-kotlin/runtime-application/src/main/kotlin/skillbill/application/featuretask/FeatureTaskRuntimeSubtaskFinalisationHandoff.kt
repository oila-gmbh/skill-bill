package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoff
import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoffInvalid
import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoffResult
import skillbill.application.featuretask.model.FeatureTaskRuntimeCommitPushHandoffValid
import skillbill.contracts.JsonCodec

private const val COMMIT_PUSH_RESULT_KEY = "commit_push_result"
private const val OUTCOME_MESSAGE_KEY = "message"
private const val CHANGED_PATHS_KEY = "changed_paths"
private const val COMMIT_SHA_KEY = "commit_sha"

object FeatureTaskRuntimeSubtaskFinalisationHandoff {
  fun readHandoff(envelope: Map<String, Any?>): FeatureTaskRuntimeCommitPushHandoffResult {
    val result = commitPushResult(envelope)
      ?: return invalid("`produced_outputs.$COMMIT_PUSH_RESULT_KEY` is absent")
    val message = result[OUTCOME_MESSAGE_KEY]?.toString()?.trim()?.takeIf(String::isNotBlank)
      ?: return invalid("`$COMMIT_PUSH_RESULT_KEY.$OUTCOME_MESSAGE_KEY` is missing or blank")
    val paths = when {
      !result.containsKey(CHANGED_PATHS_KEY) -> emptyList()
      else -> changedPaths(result) ?: return invalid(
        "`$COMMIT_PUSH_RESULT_KEY.$CHANGED_PATHS_KEY` is not a list of paths",
      )
    }
    return FeatureTaskRuntimeCommitPushHandoffValid(
      FeatureTaskRuntimeCommitPushHandoff(outcomeMessage = message, changedPaths = paths),
    )
  }

  fun withCommitSha(envelope: Map<String, Any?>, commitSha: String): Map<String, Any?> {
    val produced = JsonCodec.anyToStringAnyMap(envelope["produced_outputs"])?.toMutableMap()
      ?: return envelope
    val result = JsonCodec.anyToStringAnyMap(produced[COMMIT_PUSH_RESULT_KEY])?.toMutableMap()
      ?: return envelope
    result[COMMIT_SHA_KEY] = commitSha
    produced[COMMIT_PUSH_RESULT_KEY] = result
    return envelope.toMutableMap().apply { this["produced_outputs"] = produced }
  }

  private fun changedPaths(result: Map<String, Any?>): List<String>? =
    (result[CHANGED_PATHS_KEY] as? List<*>)?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }

  private fun commitPushResult(envelope: Map<String, Any?>): Map<String, Any?>? =
    JsonCodec.anyToStringAnyMap(envelope["produced_outputs"])?.let { produced ->
      JsonCodec.anyToStringAnyMap(produced[COMMIT_PUSH_RESULT_KEY])
    } ?: JsonCodec.anyToStringAnyMap(envelope[COMMIT_PUSH_RESULT_KEY])

  private fun invalid(detail: String) = FeatureTaskRuntimeCommitPushHandoffInvalid(
    "needs_human: commit_push completed but $detail. The runtime performs the commit and push from " +
      "that payload, so without it the subtask would publish the provisional checkpoint subject. " +
      "Re-run commit_push emitting `$COMMIT_PUSH_RESULT_KEY` with a non-blank `$OUTCOME_MESSAGE_KEY` " +
      "and an enumerated `$CHANGED_PATHS_KEY`.",
  )
}
