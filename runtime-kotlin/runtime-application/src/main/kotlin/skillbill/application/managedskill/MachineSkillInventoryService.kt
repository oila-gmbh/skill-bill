package skillbill.application.managedskill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import skillbill.application.model.MachineSkillInventoryRequest
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.MachineSkillInventorySnapshot
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsRequest
import skillbill.ports.managedskill.MachineSkillInventoryPort
import skillbill.ports.managedskill.model.InventoryTarget
import skillbill.ports.managedskill.model.ReadMachineSkillInventoryRequest

@Inject
class MachineSkillInventoryService(
  private val detector: InstallAgentTargetPort,
  private val inventory: MachineSkillInventoryPort,
) {
  suspend fun inventory(request: MachineSkillInventoryRequest): MachineSkillInventorySnapshot =
    withContext(Dispatchers.IO) {
      val detected = detector.detectAgentTargets(DetectInstallAgentTargetsRequest(request.home)).targets
        .map { target -> AgentSkillTargetId(target.name, target.path.toAbsolutePath().normalize()) }
      val duplicateIds = detected.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
      val all = (detected + request.selectedTargets).distinct().sortedBy(AgentSkillTargetId::stableIdentity)
      val targetFacts = all.map { id ->
        InventoryTarget(
          id = id,
          detected = id in detected,
          selected = id in request.selectedTargets,
          displayName = "${id.provider} — ${id.skillsPath}",
          issues = if (id in duplicateIds) listOf("duplicate detector target") else emptyList(),
        )
      }
      inventory.read(
        ReadMachineSkillInventoryRequest(
          request.home.toAbsolutePath().normalize(),
          targetFacts,
          request.includeProductDiagnostics,
        ),
      )
    }
}
