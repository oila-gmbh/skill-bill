package skillbill.cli.kernel

import com.github.ajalt.clikt.core.UsageError
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.contracts.JsonSupport

internal fun parseAgentAddonSelection(raw: String?): AgentAddonSelection {
  if (raw == null) return AgentAddonSelection()
  val root = JsonSupport.parseObjectOrNull(raw)
    ?: invalidAgentAddonSelection("--agent-addon-selection-json must be a JSON object.")
  val map = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(root))
    ?: invalidAgentAddonSelection("--agent-addon-selection-json must decode to an object.")
  if (map.keys != setOf("contract_version", "entries") || map["contract_version"] != "0.1") {
    invalidAgentAddonSelection("Agent add-on selection must contain only contract_version=0.1 and entries.")
  }
  val entries = map["entries"] as? List<*>
    ?: invalidAgentAddonSelection("Agent add-on selection entries must be an ordered array.")
  return try {
    AgentAddonSelection(
      entries.mapIndexed { index, valueEntry ->
        val entry = JsonSupport.anyToStringAnyMap(valueEntry)
          ?: invalidAgentAddonSelection("Agent add-on selection entry $index must be an object.")
        val persistedKeys = setOf("slug", "source_identity", "content_sha256")
        if (!entry.keys.containsAll(persistedKeys) || entry.keys.any { it !in persistedKeys + "description" }) {
          invalidAgentAddonSelection("Agent add-on selection entry $index has unsupported or missing fields.")
        }
        PersistedAgentAddonSelectionEntry(
          slug = entry["slug"] as? String
            ?: invalidAgentAddonSelection("Entry $index slug is required."),
          sourceIdentity = entry["source_identity"] as? String
            ?: invalidAgentAddonSelection("Entry $index source_identity is required."),
          contentSha256 = entry["content_sha256"] as? String
            ?: invalidAgentAddonSelection("Entry $index content_sha256 is required."),
        )
      },
    )
  } catch (error: IllegalArgumentException) {
    invalidAgentAddonSelection("Invalid agent add-on selection: ${error.message}", error)
  }
}

internal fun invalidAgentAddonSelection(message: String, cause: Throwable? = null): Nothing {
  throw UsageError(message).apply { cause?.let(::initCause) }
}
