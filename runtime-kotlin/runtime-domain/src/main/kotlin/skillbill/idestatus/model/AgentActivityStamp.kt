package skillbill.idestatus.model

import java.time.Instant

data class AgentActivityStamp(
  val recordedAt: Instant,
  val label: AgentActivityLabel,
) {
  init {
    require(label in AgentActivityLabel.entries) { "AgentActivityStamp.label must be a governed enum value." }
  }
}
