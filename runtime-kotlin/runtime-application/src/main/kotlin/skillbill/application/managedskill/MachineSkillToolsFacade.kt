package skillbill.application.managedskill

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.MachineSkillInventoryRequest
import skillbill.application.model.MachineSkillInstallTarget
import skillbill.application.model.MachineSkillSourceInspection
import skillbill.application.scaffold.InstallAgentService
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.InstallMachineSkillRequest
import skillbill.managedskill.model.MachineSkillOperationPreview
import skillbill.managedskill.model.MachineSkillOperationResult
import skillbill.managedskill.model.NoFollowEntryKind
import skillbill.model.EnvironmentContext
import skillbill.ports.managedskill.MachineSkillWorkspacePort
import java.nio.file.Path

@Inject
class MachineSkillToolsFacade(
  private val environment: EnvironmentContext,
  private val installAgents: InstallAgentService,
  private val workspace: MachineSkillWorkspacePort,
  private val operations: MachineSkillOperationService,
  private val lifecycle: MachineSkillLifecycleService,
  private val inventoryService: MachineSkillInventoryService,
  private val refreshService: MachineSkillRefreshService,
) {
  private val prepared = mutableMapOf<String, MachineSkillOperationPreview>()
  private val latestResults = mutableMapOf<String, MachineSkillOperationResult>()
  private val home get() = environment.userHome.toAbsolutePath().normalize()

  fun inspectSource(source: Path): MachineSkillSourceInspection = runCatching {
    MachineSkillSourceInspection(
      workspace.bundles.capture(source),
      source.toAbsolutePath().normalize(),
      emptyList(),
    )
  }.getOrElse { failure ->
    MachineSkillSourceInspection(null, source.toAbsolutePath().normalize(), listOf(failure.message ?: "Invalid skill bundle"))
  }

  fun installTargets(skillName: String): List<MachineSkillInstallTarget> =
    installAgents.detectAgentTargets(home).map { target ->
      val id = AgentSkillTargetId(target.name, target.path.toAbsolutePath().normalize())
      val destination = id.skillsPath.resolve(skillName)
      val present = workspace.targets.observe(listOf(destination)).single().kind != NoFollowEntryKind.ABSENT
      MachineSkillInstallTarget(id, detected = true, conflictPath = destination.takeIf { present })
    }

  fun previewInstall(source: Path, targets: Set<AgentSkillTargetId>): MachineSkillOperationPreview =
    operations.previewInstall(InstallMachineSkillRequest(source, targets)).also { preview ->
      preview.prepared?.plan?.planId?.let { prepared[it] = preview }
    }

  suspend fun apply(planId: String): MachineSkillOperationResult {
    val preview = prepared.remove(planId) ?: error("Prepared machine-skill plan is missing or already applied.")
    return lifecycle.apply(preview).also { latestResults[it.skillName] = it }
  }

  suspend fun inventory() = inventoryService.inventory(MachineSkillInventoryRequest(home))

  suspend fun refreshInventory() = refreshService.refresh(MachineSkillInventoryRequest(home)).snapshot

  fun managedRecord(name: String) = workspace.records.readRecord(name)

  fun managedRecordDigest(name: String) = workspace.records.recordDigest(name)

  fun canonicalSource(name: String): Path? = managedRecord(name)?.let { workspace.records.sourceRoot(name) }

  fun latestResult(name: String): MachineSkillOperationResult? = latestResults[name]
}
