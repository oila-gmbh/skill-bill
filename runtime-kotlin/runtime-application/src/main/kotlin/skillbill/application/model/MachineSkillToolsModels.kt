package skillbill.application.model

import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.ManagedSkillRecord
import skillbill.managedskill.model.OpaqueSkillBundle
import java.nio.file.Path

data class MachineSkillSourceInspection(
  val bundle: OpaqueSkillBundle?,
  val sourcePath: Path,
  val validationIssues: List<String>,
)

data class MachineSkillInstallTarget(
  val id: AgentSkillTargetId,
  val detected: Boolean,
  val conflictPath: Path?,
)

data class MachineSkillManagedDetails(
  val record: ManagedSkillRecord?,
  val canonicalSource: Path?,
  val recordDigest: String?,
)
