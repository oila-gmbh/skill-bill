package skillbill.managedskill.model

import java.nio.file.Path

enum class MachineSkillServiceOutcomeKind {
  CREATED, UNCHANGED, RETARGETED, REMOVED, SKIPPED, WARNING, FAILED, CONFLICT, BLOCKED
}

data class MachineSkillServiceOutcome(
  val kind: MachineSkillServiceOutcomeKind,
  val code: String,
  val detail: String,
  val skillName: String,
  val target: AgentSkillTargetId? = null,
  val path: Path? = null,
)

enum class MachineSkillInstallDecision { INSTALL, REINSTALL, UPDATE }

data class MachineSkillOperationPreview(
  val operation: MachineSkillMutationKind,
  val skillName: String,
  val decision: MachineSkillInstallDecision? = null,
  val prepared: PreparedMachineSkillMutation? = null,
  val outcomes: List<MachineSkillServiceOutcome>,
) {
  val blocked: Boolean = prepared == null || outcomes.any { it.kind == MachineSkillServiceOutcomeKind.BLOCKED }
}

data class MachineSkillOperationResult(
  val operation: MachineSkillMutationKind,
  val skillName: String,
  val planId: String?,
  val outcomes: List<MachineSkillServiceOutcome>,
)

data class InstallMachineSkillRequest(val source: Path, val selectedTargets: Set<AgentSkillTargetId>)
data class EditMachineSkillRequest(val name: String, val expectedRecordDigest: String, val expectedSourceHash: String)
data class SaveMachineSkillEditRequest(val edit: EditMachineSkillRequest, val skillMarkdown: String)
data class ManageMachineSkillTargetsRequest(val name: String, val selectedTargets: Set<AgentSkillTargetId>)
data class AdoptMachineSkillRequest(
  val name: String,
  val authoritativeSource: Path?,
  val replacementTargets: Set<AgentSkillTargetId>,
)
data class RepairMachineSkillRequest(val name: String)
data class DeleteMachineSkillRequest(val name: String)
data class ConfirmMachineSkillDeleteRequest(val preview: MachineSkillOperationPreview, val confirmedPlanId: String)
data class OpenMachineSkillEdit(val name: String, val skillMarkdown: String, val recordDigest: String, val sourceHash: String)

data class RefreshMachineSkillsResult(
  val snapshot: MachineSkillInventorySnapshot,
  val outcomes: List<MachineSkillServiceOutcome>,
)
