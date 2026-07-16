package skillbill.application.managedskill

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.MachineSkillInstallTarget
import skillbill.application.model.MachineSkillInventoryRequest
import skillbill.application.model.MachineSkillManagedDetails
import skillbill.application.model.MachineSkillSourceInspection
import skillbill.application.scaffold.InstallAgentService
import skillbill.install.model.InstallAgent
import skillbill.managedskill.model.AdoptMachineSkillRequest
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.DeleteMachineSkillRequest
import skillbill.managedskill.model.EditMachineSkillRequest
import skillbill.managedskill.model.InstallMachineSkillRequest
import skillbill.managedskill.model.MachineSkillOperationPreview
import skillbill.managedskill.model.MachineSkillOperationResult
import skillbill.managedskill.model.ManageMachineSkillTargetsRequest
import skillbill.managedskill.model.NoFollowEntryKind
import skillbill.managedskill.model.RepairMachineSkillRequest
import skillbill.managedskill.model.SaveMachineSkillEditRequest
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
  private val refreshService: MachineSkillRefreshService,
) {
  private val prepared = object : LinkedHashMap<String, MachineSkillOperationPreview>(
    PREPARED_PLAN_INITIAL_CAPACITY,
    PREPARED_PLAN_LOAD_FACTOR,
    true,
  ) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MachineSkillOperationPreview>?) =
      size > MAX_PREPARED_PLANS
  }
  private val latestResults = mutableMapOf<String, MachineSkillOperationResult>()
  private val home = environment.userHome.toAbsolutePath().normalize()
  private val targetCatalog by lazy {
    val detected = installAgents.detectAgentTargets(home)
      .associateBy { target -> target.name to target.path.toAbsolutePath().normalize() }
    InstallAgent.entries.map { agent ->
      val path = installAgents.agentPath(agent.id, home).toAbsolutePath().normalize()
      AgentSkillTargetId(agent.id, path) to detected.containsKey(agent.id to path)
    }
  }

  fun inspectSource(source: Path): MachineSkillSourceInspection = runCatching {
    MachineSkillSourceInspection(
      workspace.bundles.capture(source),
      source.toAbsolutePath().normalize(),
      emptyList(),
    )
  }.getOrElse { failure ->
    MachineSkillSourceInspection(
      null,
      source.toAbsolutePath().normalize(),
      listOf(failure.message ?: "Invalid skill bundle"),
    )
  }

  fun installTargets(skillName: String): List<MachineSkillInstallTarget> = targetCatalog.map { (id, detected) ->
    val destination = id.skillsPath.resolve(skillName)
    val present = workspace.targets.observe(listOf(destination)).single().kind != NoFollowEntryKind.ABSENT
    MachineSkillInstallTarget(id, detected = detected, conflictPath = destination.takeIf { present })
  }

  fun previewInstall(source: Path, targets: Set<AgentSkillTargetId>): MachineSkillOperationPreview =
    operations.previewInstall(InstallMachineSkillRequest(source, targets)).also { preview ->
      preview.prepared?.plan?.planId?.let { synchronized(prepared) { prepared[it] = preview } }
    }

  fun previewManagerAction(
    action: String,
    name: String,
    authoritativeSource: Path?,
    targets: Set<AgentSkillTargetId>,
  ): MachineSkillOperationPreview = when (action) {
    "MANAGE_AGENTS" -> operations.previewManageTargets(ManageMachineSkillTargetsRequest(name, targets))
    "ADOPT" -> operations.previewAdoption(AdoptMachineSkillRequest(name, authoritativeSource, targets))
    "REPAIR" -> operations.previewRepair(RepairMachineSkillRequest(name))
    "DELETE" -> operations.previewDelete(DeleteMachineSkillRequest(name))
    else -> error("$action does not produce a machine-skill mutation preview.")
  }.also { preview ->
    preview.prepared?.plan?.planId?.let { synchronized(prepared) { prepared[it] = preview } }
  }

  fun openEdit(name: String, recordDigest: String, sourceHash: String) =
    operations.openEdit(EditMachineSkillRequest(name, recordDigest, sourceHash))

  fun previewEdit(name: String, recordDigest: String, sourceHash: String, markdown: String) =
    operations.previewEdit(
      SaveMachineSkillEditRequest(EditMachineSkillRequest(name, recordDigest, sourceHash), markdown),
    ).also { preview ->
      preview.prepared?.plan?.planId?.let { synchronized(prepared) { prepared[it] = preview } }
    }

  suspend fun apply(planId: String): MachineSkillOperationResult {
    val preview = synchronized(prepared) { prepared.remove(planId) }
      ?: error("Prepared machine-skill plan is missing, expired, or already applied.")
    return lifecycle.apply(preview).also { latestResults[it.skillName] = it }
  }

  suspend fun inventory() = refreshService.refresh(inventoryRequest()).snapshot

  fun managedDetails(name: String): MachineSkillManagedDetails {
    val record = workspace.records.readRecord(name)
    return MachineSkillManagedDetails(
      record = record,
      canonicalSource = record?.let { workspace.records.sourceRoot(name) },
      recordDigest = record?.let { workspace.records.recordDigest(name) },
    )
  }

  fun description(name: String, occurrencePaths: List<Path>): String =
    (listOfNotNull(managedDetails(name).canonicalSource) + occurrencePaths).firstNotNullOfOrNull { source ->
      runCatching { workspace.bundles.capture(source).description }.getOrNull()
    }.orEmpty()

  fun latestResult(name: String): MachineSkillOperationResult? = latestResults[name]

  private fun inventoryRequest() = MachineSkillInventoryRequest(home, targetCatalog.map { it.first }.toSet())

  private companion object {
    const val PREPARED_PLAN_INITIAL_CAPACITY = 16
    const val PREPARED_PLAN_LOAD_FACTOR = 0.75f
    const val MAX_PREPARED_PLANS = 32
  }
}
