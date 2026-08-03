package skillbill.launcher.agentrun

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.ports.agentrun.model.AgentRunProgressEmission
import skillbill.ports.agentrun.model.AgentRunProgressEmitter
import skillbill.workflow.model.GoalProgressEventKind
import skillbill.workflow.model.GoalProgressOutcome

/**
 * Consumes only explicit provider lifecycle envelopes. Ordinary model output, process heartbeats,
 * and completion usage never become declared specialist progress or MCP startup evidence.
 */
internal class ProviderLifecycleSignals(
  private val mcpStartupEvent: (JsonNode) -> Boolean,
  private val lifecycleEvent: (JsonNode) -> ProviderLifecycleSignal?,
) {
  private val pending = StringBuilder()
  private val mapper: ObjectMapper by lazy(::ObjectMapper)
  var mcpStartupObserved: Boolean = false
    private set

  fun observe(chunk: String, progressEmitter: AgentRunProgressEmitter) {
    pending.append(chunk)
    drainLines(progressEmitter, flushPartial = false)
  }

  fun flush() {
    drainLines(AgentRunProgressEmitter.NONE, flushPartial = true)
  }

  private fun drainLines(progressEmitter: AgentRunProgressEmitter, flushPartial: Boolean) {
    while (true) {
      val newline = pending.indexOf("\n")
      if (newline < 0) break
      processLine(pending.substring(0, newline), progressEmitter)
      pending.delete(0, newline + 1)
    }
    if (flushPartial && pending.isNotBlank()) {
      processLine(pending.toString(), AgentRunProgressEmitter.NONE)
      pending.clear()
    }
  }

  private fun processLine(line: String, progressEmitter: AgentRunProgressEmitter) {
    val event = runCatching { mapper.readTree(line.trim()) }.getOrNull() ?: return
    if (mcpStartupEvent(event)) mcpStartupObserved = true

    val lifecycleSignal = lifecycleEvent(event) ?: return
    progressEmitter.emit(
      AgentRunProgressEmission(
        eventKind = lifecycleSignal.eventKind,
        processAlive = lifecycleSignal.processAlive,
        operationName = event.nonBlankText("operation_name", "delegated-review"),
        operationKind = event.nonBlankText("operation_kind", "specialist-review"),
        expectedLong = lifecycleSignal.expectedLong,
        outcome = lifecycleSignal.outcome,
        authoritative = true,
      ),
    )
  }
}

internal data class ProviderLifecycleSignal(
  val eventKind: GoalProgressEventKind,
  val outcome: GoalProgressOutcome = GoalProgressOutcome.NONE,
  val expectedLong: Boolean = false,
) {
  val processAlive: Boolean get() = eventKind != GoalProgressEventKind.OPERATION_COMPLETED
}

internal fun syntheticProviderLifecycleSignal(event: JsonNode): ProviderLifecycleSignal? {
  val type = event.textValue("type")
  val subtype = event.textValue("subtype")
  return when {
    type == "specialist_progress" || subtype == "specialist_progress" ||
      type == "operation_started" || subtype == "operation_started" ->
      ProviderLifecycleSignal(GoalProgressEventKind.OPERATION_STARTED)
    type == "operation_heartbeat" || subtype == "operation_heartbeat" ->
      ProviderLifecycleSignal(GoalProgressEventKind.OPERATION_HEARTBEAT)
    type == "operation_completed" || subtype == "operation_completed" ->
      ProviderLifecycleSignal(GoalProgressEventKind.OPERATION_COMPLETED, GoalProgressOutcome.SUCCEEDED)
    else -> null
  }
}

internal fun explicitMcpStartupEvent(event: JsonNode): Boolean {
  val type = event.textValue("type")
  val subtype = event.textValue("subtype")
  return event.path("mcp_startup_observed").asBoolean(false) ||
    type in setOf("mcp_startup", "mcp_ready") ||
    subtype in setOf("mcp_startup", "mcp_ready")
}

internal fun JsonNode.textValue(field: String): String = path(field)
  .takeIf { it.isTextual }
  ?.asText()
  .orEmpty()

internal fun JsonNode.expectedLong(): Boolean = path("expected_long")
  .takeIf { it.isBoolean }
  ?.asBoolean()
  ?: false

private fun JsonNode.nonBlankText(field: String, fallback: String): String =
  textValue(field).takeIf(String::isNotBlank) ?: fallback
