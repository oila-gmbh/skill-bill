package skillbill.infrastructure.fs.launcher.agentrun

import com.fasterxml.jackson.databind.JsonNode
import skillbill.contracts.workflow.AgentProviderEventKeys
import skillbill.error.AuditRepairProviderSessionError
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunOutputStream
import java.util.concurrent.CancellationException

internal class ProviderSessionOutputSink(
  private val decoder: AgentRunOutputDecoder,
  private val record: (String) -> Unit,
  private val downstream: AgentRunOutputSink,
) : AgentRunOutputSink {
  private val line = StringBuilder()
  private var sessionId: String? = null

  fun requireSessionId(): String = sessionId
    ?: throw AuditRepairProviderSessionError("Provider emitted no session-start identity.")

  fun finish() {
    if (line.isNotEmpty()) {
      decodeLine()
      line.setLength(0)
    }
  }

  override fun write(stream: AgentRunOutputStream, text: String) {
    if (stream == AgentRunOutputStream.STDOUT) {
      text.forEach { character ->
        if (character == '\n') {
          decodeLine()
          line.setLength(0)
        } else {
          if (line.length >= MAX_SESSION_EVENT_CHARS) {
            throw AuditRepairProviderSessionError("Session event exceeds the supported size.")
          }
          line.append(character)
        }
      }
    }
    downstream.write(stream, text)
  }

  private fun decodeLine() {
    val identity = decoder.sessionStarted(line.toString()) ?: return
    if (identity == sessionId) return
    if (sessionId != null) throw AuditRepairProviderSessionError("Provider replaced the session identity.")
    recordIdentity(identity)
    sessionId = identity
  }

  private fun recordIdentity(identity: String) {
    runCatching { record(identity) }.onFailure { error ->
      if (error is CancellationException) throw error
      throw AuditRepairProviderSessionError("Durable identity recording failed.", error)
    }
  }

  private companion object {
    const val MAX_SESSION_EVENT_CHARS = 1024 * 1024
  }
}

internal fun codexSessionStarted(event: JsonNode): String? =
  if (event.path(AgentProviderEventKeys.TYPE).asText() == "thread.started") {
    event.path(AgentProviderEventKeys.THREAD_ID).takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)
  } else {
    null
  }

internal fun claudeSessionStarted(event: JsonNode): String? =
  if (event.path(AgentProviderEventKeys.TYPE).asText() == "system" &&
    event.path(AgentProviderEventKeys.SUBTYPE).asText() == "init"
  ) {
    event.path(AgentProviderEventKeys.SESSION_ID).takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)
  } else {
    null
  }
