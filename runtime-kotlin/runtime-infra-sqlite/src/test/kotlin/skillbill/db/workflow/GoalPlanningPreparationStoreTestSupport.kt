package skillbill.db.workflow

import skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import java.nio.file.Files
import java.nio.file.Path

internal fun tempDb(): Path =
  Files.createTempDirectory("runtime-kotlin-goal-planning-preparation").resolve("metrics.db")

internal fun identity() = GoalPlanningIdentity("goal-1", "SKILL-128", "repo-root-realpath-v1:/repository")

internal fun provenance() = GoalPlanningContractProvenance(
  parentSpecHash = "a".repeat(64),
  decompositionManifestHash = "b".repeat(64),
  planningContractId = EXPECTED_SCHEMA_ID,
)

internal fun sharedCheckpoint() = SharedGoalPreplanCheckpoint(
  identity = identity(),
  provenance = provenance(),
  payloadSha256 = "c".repeat(64),
  preplanPayload = "preplan-payload",
)

internal fun descriptor(subtaskId: Int, order: Int) = GovernedGoalSubtaskDescriptor(
  subtaskId,
  order,
  ".feature-specs/SKILL-128/spec_subtask_$subtaskId.md",
  "d".repeat(64),
)

internal fun planCheckpoint(subtaskId: Int, order: Int): GoalSubtaskPlanCheckpoint {
  val descriptor = descriptor(subtaskId, order)
  return GoalSubtaskPlanCheckpoint(
    identity = identity(),
    subtaskId = subtaskId,
    manifestOrder = order,
    governedSubSpecPath = descriptor.governedSubSpecPath,
    subSpecHash = descriptor.subSpecHash,
    provenance = provenance(),
    payloadSha256 = "e".repeat(64),
    planPayload = "plan-$subtaskId",
  )
}

internal fun preparationRecord(
  parentGoalWorkflowId: String,
  subtaskId: Int,
  repositoryIdentity: String = "repo-root-realpath-v1:/repository",
  subSpecHash: String = "sub-spec-default",
): GoalPlanningPreparationRecord = GoalPlanningPreparationRecord(
  parentGoalWorkflowId = parentGoalWorkflowId,
  normalizedIssueKey = "SKILL-128",
  repositoryIdentity = repositoryIdentity,
  subtaskId = subtaskId,
  governedSubSpecPath = ".feature-specs/SKILL-128/spec_subtask_$subtaskId.md",
  preparationStatus = GoalPlanningPreparationState.PREPARED,
  provenance = GoalPlanningPreparationProvenance(
    parentSpecHash = "parent-spec-$parentGoalWorkflowId",
    subSpecHash = subSpecHash,
    decompositionManifestHash = "manifest-$parentGoalWorkflowId",
  ),
  preplanPayload = """{"phase_id":"preplan"}""",
  planPayload = """{"phase_id":"plan"}""",
)
