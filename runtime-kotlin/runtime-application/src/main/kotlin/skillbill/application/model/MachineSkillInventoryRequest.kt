package skillbill.application.model

import skillbill.managedskill.model.AgentSkillTargetId
import java.nio.file.Path

data class MachineSkillInventoryRequest(
  val home: Path,
  val selectedTargets: Set<AgentSkillTargetId> = emptySet(),
  val includeProductDiagnostics: Boolean = false,
)
