package skillbill.agentaddon

object AgentAddonAgentIds {
  val supportedIds: List<String> = listOf("claude", "codex", "junie", "cursor")

  fun parse(id: String): String {
    val normalized = id.trim().lowercase()
    require(normalized in supportedIds) {
      "Unknown agent '$id'. Supported agents: ${supportedIds.joinToString(", ")}."
    }
    return normalized
  }
}
