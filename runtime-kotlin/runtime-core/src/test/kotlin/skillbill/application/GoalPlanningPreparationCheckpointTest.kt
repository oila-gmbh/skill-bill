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
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationState
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GoalPlanningPreparationCheckpointTest {
  @Test
  fun `valid shared preplan and subtask plan are checkpointed and recovered`() {
    val harness = checkpointHarness()
    val shared = validShared()
    val plan = validPlan()

    harness.checkpoint.checkpointSharedPreplan(shared, harness.dbOverride)
    harness.checkpoint.checkpointSubtaskPlan(plan, harness.dbOverride)

    assertEquals(shared.preplanPayload, harness.readShared()?.preplanPayload)
    assertEquals(plan.planPayload, harness.readPlan()?.planPayload)
    assertEquals(GoalPlanningPreparationState.PREPARED, harness.readPlan()?.preparationStatus)
  }

  @Test
  fun `preplan payload with the wrong phase id is rejected and nothing is stored`() {
    val harness = checkpointHarness()
    val shared = validShared(payload = payloadJson(phaseId = "plan"))

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.checkpoint.checkpointSharedPreplan(shared, harness.dbOverride)
    }
    assertNull(harness.readShared())
  }

  @Test
  fun `preplan payload with an incompatible phase output version is rejected and nothing is stored`() {
    val harness = checkpointHarness()
    val shared = validShared(payload = payloadJson(phaseId = "preplan", contractVersion = "0.2"))

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.checkpoint.checkpointSharedPreplan(shared, harness.dbOverride)
    }
    assertNull(harness.readShared())
  }

  @Test
  fun `plan payload with an unsupported status is rejected and nothing is stored`() {
    val harness = checkpointHarness().withShared()
    val plan = validPlan(payload = payloadJson(phaseId = "plan", status = "queued"))

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.checkpoint.checkpointSubtaskPlan(plan, harness.dbOverride)
    }
    assertNull(harness.readPlan())
  }

  @Test
  fun `schema valid blocked plan is rejected and nothing is stored`() {
    val harness = checkpointHarness().withShared()
    val plan = validPlan(payload = payloadJson(phaseId = "plan", status = "blocked"))

    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
      harness.checkpoint.checkpointSubtaskPlan(plan, harness.dbOverride)
    }
    assertNull(harness.readPlan())
  }

  @Test
  fun `plan payload with empty produced outputs is rejected and nothing is stored`() {
    val harness = checkpointHarness().withShared()
    val plan = validPlan(payload = payloadJson(phaseId = "plan", producedOutputs = emptyMap()))

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.checkpoint.checkpointSubtaskPlan(plan, harness.dbOverride)
    }
    assertNull(harness.readPlan())
  }

  @Test
  fun `non-positive normalized subtask id is rejected and nothing is stored`() {
    val harness = checkpointHarness().withShared()
    val plan = validPlan(subtaskId = 0)

    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
      harness.checkpoint.checkpointSubtaskPlan(plan, harness.dbOverride)
    }
    assertNull(harness.readPlan(subtaskId = 0))
  }

  @Test
  fun `incompatible normalized contract version is rejected and nothing is stored`() {
    val harness = checkpointHarness().withShared()
    val plan = validPlan().copy(contractVersion = "0.1")

    assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
      harness.checkpoint.checkpointSubtaskPlan(plan, harness.dbOverride)
    }
    assertNull(harness.readPlan())
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
    fun withShared(): CheckpointHarness = apply {
      checkpoint.checkpointSharedPreplan(validShared(), dbOverride)
    }

    fun readShared(): SharedGoalPreplanCheckpoint? =
      database.read(dbOverride) { it.goalPlanningPreparations.findSharedPreplan(identity()) }

    fun readPlan(subtaskId: Int = 1): GoalSubtaskPlanCheckpoint? = database.read(dbOverride) {
      it.goalPlanningPreparations.findSubtaskPlan(identity(), subtaskId, descriptor(subtaskId).governedSubSpecPath)
    }
  }

  private companion object {
    fun identity(): GoalPlanningIdentity =
      GoalPlanningIdentity("goal-1", "SKILL-128", "repo-root-realpath-v1:/repository")

    fun provenance(): GoalPlanningContractProvenance = GoalPlanningContractProvenance(
      parentSpecHash = sha256HexUtf8("# parent"),
      decompositionManifestHash = sha256HexUtf8("# manifest"),
      planningContractId = "https://skill-bill.dev/contracts/goal-planning-preparation-schema.yaml",
    )

    fun descriptor(subtaskId: Int = 1): GovernedGoalSubtaskDescriptor = GovernedGoalSubtaskDescriptor(
      subtaskId = subtaskId,
      manifestOrder = 0,
      governedSubSpecPath = ".feature-specs/SKILL-128/spec_subtask_$subtaskId.md",
      subSpecHash = sha256HexUtf8("# subtask $subtaskId"),
    )

    fun validShared(payload: String = payloadJson("preplan")): SharedGoalPreplanCheckpoint =
      SharedGoalPreplanCheckpoint(
        identity = identity(),
        provenance = provenance(),
        payloadSha256 = sha256HexUtf8(payload),
        preplanPayload = payload,
      )

    fun validPlan(subtaskId: Int = 1, payload: String = payloadJson("plan")): GoalSubtaskPlanCheckpoint {
      val descriptor = descriptor(subtaskId)
      return GoalSubtaskPlanCheckpoint(
        identity = identity(),
        subtaskId = subtaskId,
        manifestOrder = descriptor.manifestOrder,
        governedSubSpecPath = descriptor.governedSubSpecPath,
        subSpecHash = descriptor.subSpecHash,
        provenance = provenance(),
        payloadSha256 = sha256HexUtf8(payload),
        planPayload = payload,
      )
    }

    fun payloadJson(
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
}
