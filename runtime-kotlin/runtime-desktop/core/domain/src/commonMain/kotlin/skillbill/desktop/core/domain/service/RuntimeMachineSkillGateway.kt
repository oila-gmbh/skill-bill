package skillbill.desktop.core.domain.service

import skillbill.desktop.core.domain.model.MachineSkillManagerDetail
import skillbill.desktop.core.domain.model.MachineSkillManagerRow
import skillbill.desktop.core.domain.model.MachineSkillPreviewLine
import skillbill.desktop.core.domain.model.MachineSkillSourceSummary
import skillbill.desktop.core.domain.model.MachineSkillTargetOption
import skillbill.desktop.core.domain.model.MachineSkillTargetResult

sealed interface MachineSkillSourceChoice {
  data object Cancelled : MachineSkillSourceChoice
  data class Selected(val path: String) : MachineSkillSourceChoice
  data class Failed(val message: String) : MachineSkillSourceChoice
}

data class MachineSkillInventoryPresentation(
  val rows: List<MachineSkillManagerRow>,
  val details: Map<String, MachineSkillManagerDetail>,
)

data class MachineSkillPreviewPresentation(
  val planId: String,
  val operations: List<MachineSkillPreviewLine>,
  val warnings: List<String>,
)

data class MachineSkillApplyPresentation(
  val results: List<MachineSkillTargetResult>,
  val postMortem: String? = null,
)

interface RuntimeMachineSkillGateway {
  suspend fun chooseSource(): MachineSkillSourceChoice
  suspend fun inspectSource(path: String): MachineSkillSourceSummary
  suspend fun assessInstallTargets(sourcePath: String): List<MachineSkillTargetOption>
  suspend fun previewInstall(sourcePath: String, targetIds: Set<String>): MachineSkillPreviewPresentation
  suspend fun apply(planId: String): MachineSkillApplyPresentation
  suspend fun inventory(): MachineSkillInventoryPresentation
  suspend fun refreshInventory(): MachineSkillInventoryPresentation
  suspend fun revealSource(skillName: String): Result<Unit>
  suspend fun acknowledgePostMortem(): Result<Unit>
}
