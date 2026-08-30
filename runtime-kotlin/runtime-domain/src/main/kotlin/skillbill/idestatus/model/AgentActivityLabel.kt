package skillbill.idestatus.model

enum class AgentActivityLabel(val wireValue: String) {
  WORKTREE_WRITE("worktree write"),
  STDOUT("stdout"),
  DURABLE_PROGRESS("durable progress"),
  EVIDENCE_READ("evidence read"),
  TOOL_STREAM("tool stream"),
  ;

  companion object {
    private val byWire = entries.associateBy(AgentActivityLabel::wireValue)

    fun fromWire(value: String?): AgentActivityLabel? = value?.let { byWire[it] }

    fun normalizeProbeText(value: String?): AgentActivityLabel? {
      val trimmed = value?.trim()?.takeIf(String::isNotBlank) ?: return null
      return fromWire(trimmed.lowercase())
    }
  }
}
