package skillbill.application

import skillbill.application.workflow.toEnvelopeMap
import skillbill.application.workflow.toGoalPlanningPreparationRecord
import skillbill.contracts.workflow.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.ports.persistence.model.GoalPlanningPreparationProvenance
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationState
import kotlin.test.Test
import kotlin.test.assertEquals

class GoalPlanningPreparationRecordMappingTest {
  @Test
  fun `record round-trips through the canonical envelope map`() {
    val record = GoalPlanningPreparationRecord(
      parentGoalWorkflowId = "goal-1",
      normalizedIssueKey = "SKILL-128",
      repositoryIdentity = "repo-root-realpath-v1:/repository",
      subtaskId = 2,
      governedSubSpecPath = ".feature-specs/SKILL-128/spec_subtask_2.md",
      preparationStatus = GoalPlanningPreparationState.PREPARED,
      provenance = GoalPlanningPreparationProvenance(
        parentSpecHash = "parent-hash",
        subSpecHash = "sub-hash",
        decompositionManifestHash = "manifest-hash",
        phaseOutputContractId = "phase-output-contract-id-fixture",
        phaseOutputContractVersion = "phase-output-contract-version-fixture",
      ),
      preplanPayload = """{"phase_id":"preplan"}""",
      planPayload = """{"phase_id":"plan"}""",
    )

    val envelope = record.toEnvelopeMap()
    val roundTripped = envelope.toGoalPlanningPreparationRecord()

    assertEquals("goal-1", envelope["parent_goal_workflow_id"])
    assertEquals(2, envelope["subtask_id"])
    assertEquals("prepared", envelope["preparation_status"])
    assertEquals(GOAL_PLANNING_PREPARATION_CONTRACT_VERSION, envelope["contract_version"])
    val provenanceMap = envelope["provenance"] as Map<*, *>
    assertEquals(record.provenance.phaseOutputContractId, provenanceMap["phase_output_contract_id"])
    assertEquals(record.provenance.phaseOutputContractVersion, provenanceMap["phase_output_contract_version"])
    assertEquals(record.copy(createdAt = roundTripped.createdAt, updatedAt = roundTripped.updatedAt), roundTripped)
  }
}
