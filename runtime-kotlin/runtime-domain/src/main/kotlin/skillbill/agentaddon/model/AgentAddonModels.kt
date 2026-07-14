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
