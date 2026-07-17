package skillbill.application

import skillbill.application.featuretask.sha256HexUtf8
import skillbill.application.workflow.GoalPlanningPreparationCheckpoint
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.infrastructure.fs.GoalPlanningPreparationEnvelopeValidatorAdapter
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.model.EnvironmentContext
import skillbill.ports.persistence.model.GoalPlanningPreparationProvenance
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationState
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoalPlanningPreparationCheckpointTest {
  @Test
  fun `a valid prepared pair is checkpointed and recovered`() {
    val harness = checkpointHarness()
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)

    harness.checkpoint.checkpoint(record, harness.dbOverride)

    val recovered = harness.read("goal-1", 1)
    assertEquals(GoalPlanningPreparationState.PREPARED, recovered?.preparationStatus)
    assertEquals(record.preplanPayload, recovered?.preplanPayload)
    assertEquals(record.planPayload, recovered?.planPayload)
    assertEquals(record.provenance, recovered?.provenance)
  }

  @Test
  fun `a preplan payload with the wrong phase id is rejected and nothing is stored`() {
    val harness = checkpointHarness()
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
      .copy(preplanPayload = payloadJson(phaseId = "plan"))

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.checkpoint.checkpoint(record, harness.dbOverride)
    }
    assertNull(harness.read("goal-1", 1))
  }

  @Test
  fun `a preplan payload with an incompatible phase output contract version is rejected and nothing is stored`() {
    val harness = checkpointHarness()
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
      .copy(preplanPayload = payloadJson(phaseId = "preplan", contractVersion = "0.2"))

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.checkpoint.checkpoint(record, harness.dbOverride)
    }
    assertNull(harness.read("goal-1", 1))
  }

  @Test
  fun `a plan payload with an unsupported status is rejected and nothing is stored`() {
    val harness = checkpointHarness()
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
      .copy(planPayload = payloadJson(phaseId = "plan", status = "queued"))

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.checkpoint.checkpoint(record, harness.dbOverride)
    }
    assertNull(harness.read("goal-1", 1))
  }

  @Test
  fun `a plan payload with empty produced outputs is rejected and nothing is stored`() {
    val harness = checkpointHarness()
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
      .copy(planPayload = payloadJson(phaseId = "plan", producedOutputs = emptyMap<String, Any?>()))

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.checkpoint.checkpoint(record, harness.dbOverride)
    }
    assertNull(harness.read("goal-1", 1))
  }

  @Test
  fun `a malformed envelope with a non-positive subtask id is rejected at the seam and nothing is stored`() {
    val harness = checkpointHarness()
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 0)

    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
      harness.checkpoint.checkpoint(record, harness.dbOverride)
    }
    assertNull(harness.read("goal-1", 0))
  }

  @Test
  fun `a malformed envelope with an incompatible contract version is rejected and nothing is stored`() {
    val harness = checkpointHarness()
    val record = validRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(contractVersion = "0.2")

    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
      harness.checkpoint.checkpoint(record, harness.dbOverride)
    }
    assertNull(harness.read("goal-1", 1))
  }

  private fun checkpointHarness(): CheckpointHarness {
    val tempDir = Files.createTempDirectory("goal-planning-checkpoint")
    val dbOverride = tempDir.resolve("metrics.db").toString()
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(environment = emptyMap(), userHome = tempDir))
    val checkpoint = GoalPlanningPreparationCheckpoint(
      database = database,
      envelopeValidator = GoalPlanningPreparationEnvelopeValidatorAdapter(),
      phaseOutputValidator = FeatureTaskRuntimePhaseOutputValidatorAdapter(),
    )
    return CheckpointHarness(checkpoint, database, dbOverride)
  }

  private data class CheckpointHarness(
    val checkpoint: GoalPlanningPreparationCheckpoint,
    private val database: SQLiteDatabaseSessionFactory,
    val dbOverride: String,
  ) {
    fun read(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord? =
      database.read(dbOverride) { unitOfWork ->
        unitOfWork.goalPlanningPreparations.findByGoalAndSubtask(parentGoalWorkflowId, subtaskId)
      }
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
    producedOutputs: Map<String, Any?> = mapOf("mode" to "implement"),
  ): String {
    val outputs = producedOutputs.entries.joinToString(",") { (key, value) -> "\"$key\":\"$value\"" }
    return """
    {"contract_version":"$contractVersion","phase_id":"$phaseId","status":"$status","summary":"s","produced_outputs":{$outputs}}
    """.trimIndent()
  }
}
