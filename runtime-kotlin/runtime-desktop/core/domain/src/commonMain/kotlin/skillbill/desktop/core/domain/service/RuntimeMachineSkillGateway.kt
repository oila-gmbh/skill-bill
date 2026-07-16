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
  val inventory: MachineSkillInventoryPresentation? = null,
  val inventoryError: String? = null,
)

data class ManagedMachineSkillEditPresentation(
  val name: String,
  val markdown: String,
  val recordIdentity: String,
  val sourceIdentity: String,
)

interface RuntimeMachineSkillGateway {
  suspend fun chooseSource(): MachineSkillSourceChoice
  suspend fun inspectSource(path: String): MachineSkillSourceSummary
  suspend fun assessInstallTargets(sourcePath: String): List<MachineSkillTargetOption>
  suspend fun previewInstall(sourcePath: String, targetIds: Set<String>): MachineSkillPreviewPresentation
  suspend fun apply(planId: String): MachineSkillApplyPresentation
  suspend fun previewManagerAction(
    action: String,
    name: String,
    authoritativeSource: String?,
    targetIds: Set<String>,
  ): MachineSkillPreviewPresentation
  suspend fun inventory(): MachineSkillInventoryPresentation
  suspend fun refreshInventory(): MachineSkillInventoryPresentation
  suspend fun openManagedEdit(
    name: String,
    recordIdentity: String,
    sourceIdentity: String,
  ): ManagedMachineSkillEditPresentation
  suspend fun previewManagedEdit(edit: ManagedMachineSkillEditPresentation): MachineSkillPreviewPresentation
  suspend fun revealSource(skillName: String): Result<Unit>
  suspend fun acknowledgePostMortem(): Result<Unit>
}

object UnavailableRuntimeMachineSkillGateway : RuntimeMachineSkillGateway {
  private fun unavailable(): Nothing = error("Machine-skill gateway is unavailable.")
  override suspend fun chooseSource() = unavailable()
  override suspend fun inspectSource(path: String) = unavailable()
  override suspend fun assessInstallTargets(sourcePath: String) = unavailable()
  override suspend fun previewInstall(sourcePath: String, targetIds: Set<String>) = unavailable()
  override suspend fun apply(planId: String) = unavailable()
  override suspend fun previewManagerAction(
    action: String,
    name: String,
    authoritativeSource: String?,
    targetIds: Set<String>,
  ) = unavailable()
  override suspend fun inventory() = unavailable()
  override suspend fun refreshInventory() = unavailable()
  override suspend fun openManagedEdit(name: String, recordIdentity: String, sourceIdentity: String) = unavailable()
  override suspend fun previewManagedEdit(edit: ManagedMachineSkillEditPresentation) = unavailable()
  override suspend fun revealSource(skillName: String): Result<Unit> =
    Result.failure(IllegalStateException("Machine-skill gateway is unavailable."))
  override suspend fun acknowledgePostMortem(): Result<Unit> = Result.failure(
    IllegalStateException("Machine-skill gateway is unavailable."),
  )
}
