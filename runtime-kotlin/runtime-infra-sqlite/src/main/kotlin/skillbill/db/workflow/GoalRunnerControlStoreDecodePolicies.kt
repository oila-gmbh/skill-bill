package skillbill.db.workflow

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.review.context.model.CodeReviewExecutionMode
import kotlin.coroutines.cancellation.CancellationException

internal fun decodeReviewPolicy(raw: String): GoalRunnerReviewPolicy {
  val policy = JsonSupport.parseObjectOrNull(raw)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: goalRunnerControlSchemaError("review policy durable record must be an object.")
  val mode = policy["code_review_mode"] as? String
    ?: goalRunnerControlSchemaError("review policy durable record is missing code_review_mode.")
  val codeReviewMode = CodeReviewExecutionMode.fromWire(mode)
  val addOns = (policy["agent_addon_selection"] as? List<*>).orEmpty().mapIndexed { index, value ->
    decodeReviewPolicyAddonEntry(index, value)
  }
  return GoalRunnerReviewPolicy(codeReviewMode, AgentAddonSelection(addOns))
}

internal fun decodeAcceptances(raw: String): Map<Int, GoalRunnerOutOfBandAcceptance> =
  parseAcceptanceList(raw).associate(::decodeAcceptanceEntry)

private fun decodeReviewPolicyAddonEntry(index: Int, value: Any?): PersistedAgentAddonSelectionEntry {
  val entry = JsonSupport.anyToStringAnyMap(value)
    ?: goalRunnerControlSchemaError("review policy durable add-on entry $index must be a map.")
  return PersistedAgentAddonSelectionEntry(
    slug = requireReviewPolicyAddonField(entry, index, "slug"),
    sourceIdentity = requireReviewPolicyAddonField(entry, index, "source_identity"),
    contentSha256 = requireReviewPolicyAddonField(entry, index, "content_sha256"),
  )
}

private fun requireReviewPolicyAddonField(entry: Map<String, Any?>, index: Int, key: String): String =
  entry[key] as? String
    ?: goalRunnerControlSchemaError("review policy durable add-on entry $index is missing $key.")

private fun parseAcceptanceList(raw: String): List<*> {
  val values = JsonSupport.jsonElementToValue(parseAcceptanceJsonElement(raw)) as? List<*>
  return values ?: goalRunnerControlSchemaError("acceptance durable record must be a list.")
}

private fun parseAcceptanceJsonElement(raw: String): JsonElement = try {
  JsonSupport.json.parseToJsonElement(raw)
} catch (error: CancellationException) {
  throw error
} catch (error: SerializationException) {
  invalidAcceptanceJson(error)
} catch (error: IllegalArgumentException) {
  invalidAcceptanceJson(error)
}

private fun invalidAcceptanceJson(cause: Throwable): Nothing = throw InvalidWorkflowStateSchemaError(
  "Goal runner control state: acceptance durable record is not valid JSON.",
  cause,
)

private fun decodeAcceptanceEntry(value: Any?): Pair<Int, GoalRunnerOutOfBandAcceptance> {
  val entry = JsonSupport.anyToStringAnyMap(value)
    ?: goalRunnerControlSchemaError("acceptance durable record entries must be maps.")
  val acceptance = GoalRunnerOutOfBandAcceptance(
    subtaskId = requireAcceptanceInt(entry, "subtask_id"),
    commitSha = requireAcceptanceString(entry, "commit_sha"),
    reason = requireAcceptanceString(entry, "reason"),
    acceptedAt = requireAcceptanceString(entry, "accepted_at"),
  )
  return acceptance.subtaskId to acceptance
}

private fun requireAcceptanceInt(entry: Map<String, Any?>, key: String): Int = (entry[key] as? Number)?.toInt()
  ?: goalRunnerControlSchemaError("acceptance durable record entry is missing $key.")

private fun requireAcceptanceString(entry: Map<String, Any?>, key: String): String = entry[key] as? String
  ?: goalRunnerControlSchemaError("acceptance durable record entry is missing $key.")
