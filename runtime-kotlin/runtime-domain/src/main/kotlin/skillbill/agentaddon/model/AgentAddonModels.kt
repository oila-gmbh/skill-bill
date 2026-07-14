package skillbill.agentaddon.model

import skillbill.install.model.InstallAgent
import java.nio.file.Path

enum class AgentAddonConsumer(val id: String) {
  BILL_FEATURE("bill-feature"),
  ;

  companion object {
    fun fromId(id: String): AgentAddonConsumer = entries.firstOrNull { it.id == id }
      ?: throw IllegalArgumentException(
        "Unknown agent add-on consumer '$id'. Supported: ${entries.joinToString { it.id }}.",
      )
  }
}

data class AgentAddonDeclaration(
  val contractVersion: String,
  val slug: String,
  val description: String,
  val agents: List<InstallAgent>,
  val consumers: List<AgentAddonConsumer>,
  val addonRoot: Path,
  val manifestPath: Path,
  val contentPath: Path,
  val canonicalSourceIdentity: Path,
)

data class AgentAddonCatalogueEntry(
  val identity: String,
  val slug: String,
  val description: String,
  val agentIds: List<String>,
  val consumers: List<String>,
  val manifestPath: Path,
  val contentPath: Path,
)

data class PersistedAgentAddonSelectionEntry(
  val slug: String,
  val sourceIdentity: String,
  val contentSha256: String,
) {
  init {
    require(slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Invalid agent add-on slug '$slug'." }
    require(contentSha256.matches(Regex("[0-9a-f]{64}"))) {
      "Agent add-on '$slug' content digest must be a lowercase SHA-256 value."
    }
    require(sourceIdentity.isNotBlank()) { "Agent add-on '$slug' source identity is required." }
  }
}

data class HydratedAgentAddonSelectionEntry(
  val persisted: PersistedAgentAddonSelectionEntry,
  val description: String,
  val content: String,
)

data class AgentAddonSelection(
  val entries: List<PersistedAgentAddonSelectionEntry> = emptyList(),
) {
  init {
    require(entries.map { it.slug }.distinct().size == entries.size) {
      "Agent add-on selection contains duplicate slugs."
    }
  }
}

data class HydratedAgentAddonSelection(
  val entries: List<HydratedAgentAddonSelectionEntry> = emptyList(),
) {
  val persisted: AgentAddonSelection get() = AgentAddonSelection(entries.map { it.persisted })
}
