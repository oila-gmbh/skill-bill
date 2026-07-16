package skillbill.desktop.core.domain.model

enum class MachineToolAction { OPEN_CATALOG, INSTALL_SKILL, MANAGE_SKILLS }
enum class MachineToolMutationRisk { READ_ONLY, MUTATES_MACHINE }

data class MachineToolAvailability(val available: Boolean = true, val reason: String? = null)

data class MachineToolDescriptor(
  val id: String,
  val title: String,
  val shortDescription: String,
  val marker: String,
  val mutationRisk: MachineToolMutationRisk,
  val availability: MachineToolAvailability = MachineToolAvailability(),
  val action: MachineToolAction,
)

object MachineToolRegistry {
  val tools = listOf(
    MachineToolDescriptor(
      id = "install-skill-to-agents",
      title = "Install skill to agents",
      shortDescription = "Choose a SKILL.md bundle and install it to one or more detected agents.",
      marker = "in",
      mutationRisk = MachineToolMutationRisk.MUTATES_MACHINE,
      action = MachineToolAction.INSTALL_SKILL,
    ),
    MachineToolDescriptor(
      id = "manage-installed-skills",
      title = "Manage installed skills",
      shortDescription = "Inspect, repair, retarget, adopt, edit, or remove machine skills.",
      marker = "mg",
      mutationRisk = MachineToolMutationRisk.MUTATES_MACHINE,
      action = MachineToolAction.MANAGE_SKILLS,
    ),
  )
}

enum class MachineToolsSurface { CATALOG, INSTALL, MANAGER }
enum class MachineSkillOwnershipFilter { ALL, MANAGED, UNMANAGED, CONFLICT }
enum class MachineSkillHealthFilter { ALL, HEALTHY, NEEDS_ATTENTION }

data class MachineSkillSourceSummary(
  val skillName: String,
  val description: String,
  val sourcePath: String,
  val includedFileCount: Int,
  val totalBytes: Long,
  val contentIdentity: String,
  val validationIssues: List<String> = emptyList(),
)

data class MachineSkillTargetOption(
  val id: String,
  val provider: String,
  val path: String,
  val detected: Boolean,
  val conflict: String? = null,
  val selected: Boolean = detected && conflict == null,
)

data class MachineSkillPreviewLine(val operation: String, val path: String, val detail: String)
data class MachineSkillTargetResult(val targetId: String, val outcome: String, val detail: String)

enum class MachineSkillInstallStep { SOURCE, TARGETS, PREVIEW, APPLYING, RESULTS }

data class MachineSkillInstallState(
  val step: MachineSkillInstallStep = MachineSkillInstallStep.SOURCE,
  val source: MachineSkillSourceSummary? = null,
  val targets: List<MachineSkillTargetOption> = emptyList(),
  val planId: String? = null,
  val preview: List<MachineSkillPreviewLine> = emptyList(),
  val warnings: List<String> = emptyList(),
  val results: List<MachineSkillTargetResult> = emptyList(),
  val error: String? = null,
) {
  val canContinue: Boolean get() = source != null && source.validationIssues.isEmpty()
  val canPreview: Boolean get() = targets.any { it.selected }
}

data class MachineSkillTargetDetail(
  val provider: String,
  val path: String,
  val detectionStatus: String,
  val state: String,
  val contentIdentity: String? = null,
)

data class MachineSkillManagerRow(
  val name: String,
  val description: String,
  val ownership: String,
  val health: String,
  val agents: Set<String>,
)

data class MachineSkillManagerDetail(
  val name: String,
  val description: String,
  val ownership: String,
  val provenance: List<String>,
  val canonicalManagedSourcePath: String?,
  val activeSnapshotHash: String?,
  val recordIdentity: String?,
  val contentIdentity: String?,
  val targets: List<MachineSkillTargetDetail>,
  val validationIssues: List<String>,
  val lastMutationResult: List<MachineSkillTargetResult> = emptyList(),
  val repairAvailable: Boolean = false,
)

data class MachineSkillManagerState(
  val rows: List<MachineSkillManagerRow> = emptyList(),
  val query: String = "",
  val ownershipFilter: MachineSkillOwnershipFilter = MachineSkillOwnershipFilter.ALL,
  val healthFilter: MachineSkillHealthFilter = MachineSkillHealthFilter.ALL,
  val agentFilter: String? = null,
  val selectedName: String? = null,
  val detail: MachineSkillManagerDetail? = null,
  val loading: Boolean = false,
  val error: String? = null,
)

data class MachineToolsState(
  val surface: MachineToolsSurface? = null,
  val descriptors: List<MachineToolDescriptor> = MachineToolRegistry.tools,
  val install: MachineSkillInstallState = MachineSkillInstallState(),
  val manager: MachineSkillManagerState = MachineSkillManagerState(),
  val machineMutationBusy: Boolean = false,
  val postMortem: String? = null,
)
