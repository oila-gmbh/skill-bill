package skillbill.application.managedskill

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.MachineSkillInventoryRequest
import skillbill.managedskill.model.MachineSkillServiceOutcome
import skillbill.managedskill.model.MachineSkillServiceOutcomeKind
import skillbill.managedskill.model.RefreshMachineSkillsResult

@Inject
class MachineSkillRefreshService(private val inventoryService: MachineSkillInventoryService) {
  suspend fun refresh(request: MachineSkillInventoryRequest): RefreshMachineSkillsResult {
    val snapshot = inventoryService.inventory(request)
    val rowOutcomes = snapshot.rows.map { row ->
      val kind = if (row.issues.isEmpty()) MachineSkillServiceOutcomeKind.UNCHANGED else MachineSkillServiceOutcomeKind.WARNING
      MachineSkillServiceOutcome(kind, "inventory-${kind.name.lowercase()}", row.issues.joinToString { it.message }.ifEmpty { "Inventory refreshed" }, row.normalizedName)
    }
    val diagnostics = snapshot.diagnostics.map { diagnostic ->
      MachineSkillServiceOutcome(MachineSkillServiceOutcomeKind.WARNING, diagnostic.kind, diagnostic.message, "inventory", path = diagnostic.path)
    }
    return RefreshMachineSkillsResult(snapshot, rowOutcomes + diagnostics)
  }
}
