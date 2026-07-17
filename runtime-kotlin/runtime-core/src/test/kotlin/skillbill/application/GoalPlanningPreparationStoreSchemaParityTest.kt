package skillbill.application

import skillbill.contracts.workflow.GoalPlanningPreparationSchemaValidator
import skillbill.db.core.DatabaseRuntime
import skillbill.db.workflow.GoalPlanningPreparationStore
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.persistence.model.GoalPlanningPreparationProvenance
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoalPlanningPreparationStoreSchemaParityTest {
  @Test
  fun `the canonical schema and the store both accept a valid envelope`() {
    val record = defaultRecord()

    GoalPlanningPreparationSchemaValidator.validate(canonicalEnvelope(record), label(record))
    withStore { store ->
      store.markPrepared(record)
      val recovered = store.findByGoalAndSubtask(record.parentGoalWorkflowId, record.subtaskId)
      assertEquals(GoalPlanningPreparationState.PREPARED, recovered?.preparationStatus)
      assertEquals(record.provenance, recovered?.provenance)
    }
  }

  @Test
  fun `contract_version const is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(defaultRecord().copy(contractVersion = "0.2"))
  }

  @Test
  fun `subtask_id minimum is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(baseRecord(parentGoalWorkflowId = "goal-1", subtaskId = 0))
  }

  @Test
  fun `parent_goal_workflow_id minLength is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(defaultRecord().copy(parentGoalWorkflowId = ""))
  }

  @Test
  fun `normalized_issue_key minLength is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(defaultRecord().copy(normalizedIssueKey = ""))
  }

  @Test
  fun `repository_identity minLength is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(defaultRecord().copy(repositoryIdentity = ""))
  }

  @Test
  fun `governed_sub_spec_path minLength is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(defaultRecord().copy(governedSubSpecPath = ""))
  }

  @Test
  fun `preplan_payload minLength is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(defaultRecord().copy(preplanPayload = ""))
  }

  @Test
  fun `plan_payload minLength is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(defaultRecord().copy(planPayload = ""))
  }

  @Test
  fun `provenance parent_spec_hash minLength is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(
      defaultRecord().copy(
        provenance = baseProvenance().copy(parentSpecHash = ""),
      ),
    )
  }

  @Test
  fun `provenance sub_spec_hash minLength is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(
      defaultRecord().copy(
        provenance = baseProvenance().copy(subSpecHash = ""),
      ),
    )
  }

  @Test
  fun `provenance decomposition_manifest_hash minLength is enforced by the store and the canonical schema`() {
    assertStoreAndCanonicalReject(
      defaultRecord().copy(
        provenance = baseProvenance().copy(decompositionManifestHash = ""),
      ),
    )
  }

  @Test
  fun `preparation_status must be prepared is a store coherence invariant beyond the schema enum`() {
    assertStoreRejectsOnly(
      defaultRecord().copy(
        preparationStatus = GoalPlanningPreparationState.PENDING,
      ),
    )
  }

  @Test
  fun `provenance phase_output_contract_id binding is a store coherence invariant beyond the schema`() {
    assertStoreRejectsOnly(
      defaultRecord().copy(
        provenance = baseProvenance().copy(phaseOutputContractId = "not-the-phase-output-schema-id"),
      ),
    )
  }

  @Test
  fun `provenance phase_output_contract_version binding is a store coherence invariant beyond the schema`() {
    assertStoreRejectsOnly(
      defaultRecord().copy(
        provenance = baseProvenance().copy(phaseOutputContractVersion = "9.9"),
      ),
    )
  }

  private fun assertStoreAndCanonicalReject(violating: GoalPlanningPreparationRecord) {
    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
      GoalPlanningPreparationSchemaValidator.validate(canonicalEnvelope(violating), label(violating))
    }
    withStore { store ->
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(violating) }
      assertNull(store.findByGoalAndSubtask(violating.parentGoalWorkflowId, violating.subtaskId))
    }
  }

  private fun assertStoreRejectsOnly(violating: GoalPlanningPreparationRecord) {
    GoalPlanningPreparationSchemaValidator.validate(canonicalEnvelope(violating), label(violating))
    withStore { store ->
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(violating) }
      assertNull(store.findByGoalAndSubtask(violating.parentGoalWorkflowId, violating.subtaskId))
    }
  }

  private fun withStore(block: (GoalPlanningPreparationStore) -> Unit) {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection -> block(GoalPlanningPreparationStore(connection)) }
  }

  private fun label(record: GoalPlanningPreparationRecord): String =
    "${record.parentGoalWorkflowId}#${record.subtaskId}"

  private fun canonicalEnvelope(record: GoalPlanningPreparationRecord): Map<String, Any?> = linkedMapOf(
    "contract_version" to record.contractVersion,
    "parent_goal_workflow_id" to record.parentGoalWorkflowId,
    "normalized_issue_key" to record.normalizedIssueKey,
    "repository_identity" to record.repositoryIdentity,
    "subtask_id" to record.subtaskId,
    "governed_sub_spec_path" to record.governedSubSpecPath,
    "preparation_status" to record.preparationStatus.wireValue,
    "provenance" to linkedMapOf(
      "parent_spec_hash" to record.provenance.parentSpecHash,
      "sub_spec_hash" to record.provenance.subSpecHash,
      "decomposition_manifest_hash" to record.provenance.decompositionManifestHash,
      "phase_output_contract_id" to record.provenance.phaseOutputContractId,
      "phase_output_contract_version" to record.provenance.phaseOutputContractVersion,
    ),
    "preplan_payload" to record.preplanPayload,
    "plan_payload" to record.planPayload,
  )

  private fun baseProvenance(): GoalPlanningPreparationProvenance = GoalPlanningPreparationProvenance(
    parentSpecHash = "parent-spec",
    subSpecHash = "sub-spec",
    decompositionManifestHash = "manifest",
  )

  private fun defaultRecord(): GoalPlanningPreparationRecord =
    baseRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)

  private fun baseRecord(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord =
    GoalPlanningPreparationRecord(
      parentGoalWorkflowId = parentGoalWorkflowId,
      normalizedIssueKey = "SKILL-128",
      repositoryIdentity = "repo-root-realpath-v1:/repository",
      subtaskId = subtaskId,
      governedSubSpecPath = ".feature-specs/SKILL-128/spec_subtask_$subtaskId.md",
      preparationStatus = GoalPlanningPreparationState.PREPARED,
      provenance = baseProvenance(),
      preplanPayload = """{"phase_id":"preplan"}""",
      planPayload = """{"phase_id":"plan"}""",
    )

  private fun tempDb(): Path =
    Files.createTempDirectory("runtime-kotlin-goal-planning-preparation-parity").resolve("metrics.db")
}
