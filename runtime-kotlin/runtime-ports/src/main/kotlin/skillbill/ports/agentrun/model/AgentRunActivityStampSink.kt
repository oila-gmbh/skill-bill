package skillbill.ports.agentrun.model

import skillbill.idestatus.model.AgentActivityLabel

fun interface AgentRunActivityStampSink {
  fun stamp(label: AgentActivityLabel)

  companion object {
    val NONE: AgentRunActivityStampSink = AgentRunActivityStampSink { }
  }
}
