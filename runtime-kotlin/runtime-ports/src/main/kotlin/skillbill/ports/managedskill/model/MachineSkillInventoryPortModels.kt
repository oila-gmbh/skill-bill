package skillbill.ports.managedskill.model

import skillbill.managedskill.model.AgentSkillTargetId
import java.nio.file.Path

data class ReadMachineSkillInventoryRequest(
  val home: Path,
  val targets: List<InventoryTarget>,
  val includeProductDiagnostics: Boolean,
)

data class InventoryTarget(
  val id: AgentSkillTargetId,
  val detected: Boolean,
  val selected: Boolean,
  val displayName: String,
  val issues: List<String> = emptyList(),
)

data class SnapshotReferenceDiscovery(val references: Set<Path>, val complete: Boolean, val warnings: List<String>)
