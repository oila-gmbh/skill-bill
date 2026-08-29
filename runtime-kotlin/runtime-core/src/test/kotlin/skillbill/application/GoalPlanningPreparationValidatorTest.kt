package skillbill.application

import skillbill.application.goalrunner.planning.GoalPlanningPreparationValidator
import skillbill.application.goalrunner.planning.sha256HexUtf8
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.contracts.workflow.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import skillbill.ports.goalrunner.model.GoalPlanningPreparationProvenance
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalPlanningPreparationValidatorTest {
  // The real projection validator, not a stand-in: the write path must reject exactly what the
  // consumer launch seam rejects, and a weaker validator here would prove nothing about that.
  private val validator = GoalPlanningPreparationValidator(
    FeatureTaskRuntimePhaseOutputValidatorAdapter(),
    FeatureTaskRuntimePlanningProjectionValidatorAdapter(),
  )

  @Test
  fun `a valid preplan and plan pair is accepted`() {
    validator.validate(validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1))
  }

  @Test
  fun `a projection-valid pair still checkpoints unchanged after the producer gate is added`() {
    // Acceptance side of AC-004: the gate narrows nothing that was already valid.
    validator.validate(validRecord(parentGoalWorkflowId = "goal-2", subtaskId = 3))
  }

  @Test
  fun `a plan payload missing value is rejected at write time`() {
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
      planPayload = payloadJson(phaseId = "plan", producedOutputsJson = """{"prompt":"optional only"}"""),
    )

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> { validator.validate(record) }
    assertTrue(
      error.reason.contains("value"),
      "the rejection must name the offending field so the fix loop can act on it: ${error.reason}",
    )
  }

  @Test
  fun `a preplan payload missing value is rejected at write time`() {
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
      preplanPayload = payloadJson(phaseId = "preplan", producedOutputsJson = """{"prompt":"optional only"}"""),
    )

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> { validator.validate(record) }
  }

  @Test
  fun `a plan payload in the preplan slot is rejected because phase_id must match the source label`() {
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
      preplanPayload = payloadJson(phaseId = "plan"),
    )

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> { validator.validate(record) }
  }

  @Test
  fun `a payload with an incompatible phase output contract version is rejected`() {
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
      preplanPayload = payloadJson(phaseId = "preplan", contractVersion = "9.9"),
    )

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> { validator.validate(record) }
  }

  @Test
  fun `a payload with an unsupported status is rejected`() {
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
      planPayload = payloadJson(phaseId = "plan", status = "queued"),
    )

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> { validator.validate(record) }
  }

  @Test
  fun `a payload with empty produced_outputs is rejected`() {
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
      planPayload = payloadJson(phaseId = "plan", producedOutputsJson = "{}"),
    )

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> { validator.validate(record) }
  }

  @Test
  fun `an envelope with an incompatible envelope contract version is rejected`() {
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(contractVersion = "0.2")

    val error = assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { validator.validate(record) }
    assertEquals("goal-1#1", error.sourceLabel)
  }

  @Test
  fun `an envelope with pending status is rejected at the checkpoint seam`() {
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
      preparationStatus = GoalPlanningPreparationState.PENDING,
    )

    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { validator.validate(record) }
  }

  @Test
  fun `sha256 of utf8 bytes is deterministic and stable across re-reads`() {
    val first = sha256HexUtf8("# SKILL-128 parent spec")
    val second = sha256HexUtf8("# SKILL-128 parent spec")

    assertEquals(64, first.length)
    assertEquals(first, second)
    assertEquals(first.lowercase(), first)
  }

  @Test
  fun `default provenance pins the reused phase output contract identity`() {
    val provenance = GoalPlanningPreparationProvenance(
      parentSpecHash = "p",
      subSpecHash = "s",
      decompositionManifestHash = "m",
    )

    assertEquals(FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID, provenance.phaseOutputContractId)
    assertEquals(FEATURE_TASK_RUNTIME_CONTRACT_VERSION, provenance.phaseOutputContractVersion)
    assertEquals(GOAL_PLANNING_PREPARATION_CONTRACT_VERSION, GOAL_PLANNING_PREPARATION_CONTRACT_VERSION)
  }

  private fun validRecord(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord =
    GoalPlanningPreparationRecord(
      parentGoalWorkflowId = parentGoalWorkflowId,
      normalizedIssueKey = "SKILL-128",
      repositoryIdentity = "repo-root-realpath-v1:/repository",
      subtaskId = subtaskId,
      governedSubSpecPath = ".feature-specs/SKILL-128/spec_subtask_$subtaskId.md",
      preparationStatus = GoalPlanningPreparationState.PREPARED,
      provenance = GoalPlanningPreparationProvenance(
        parentSpecHash = sha256HexUtf8("# parent"),
        subSpecHash = sha256HexUtf8("# subtask $subtaskId"),
        decompositionManifestHash = sha256HexUtf8("# manifest"),
      ),
      preplanPayload = payloadJson(phaseId = "preplan"),
      planPayload = payloadJson(phaseId = "plan"),
    )

  private fun payloadJson(
    phaseId: String,
    contractVersion: String = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
    status: String = "completed",
    producedOutputsJson: String = defaultProjectionJson(phaseId),
  ): String = """
    {"contract_version":"$contractVersion","phase_id":"$phaseId","status":"$status","summary":"s",
    "produced_outputs":$producedOutputsJson}
  """.trimIndent().replace("\n", "")

  private fun defaultProjectionJson(phaseId: String): String =
    if (phaseId == "preplan") preplanProjectionJson else planProjectionJson

  private val preplanProjectionJson =
    """{"value":"Producer may omit test obligations in prose."}"""

  private val planProjectionJson =
    """{"value":"Producer may omit test obligations in prose."}"""
}
