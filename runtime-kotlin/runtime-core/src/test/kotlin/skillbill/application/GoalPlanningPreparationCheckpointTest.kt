package skillbill.application

import skillbill.application.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.application.goalplanning.sha256HexUtf8
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePhaseOutputValidatorAdapter
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import skillbill.infrastructure.fs.GoalPlanningPreparationEnvelopeValidatorAdapter
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.model.EnvironmentContext
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationState
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairOperation
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
  fun `repaired planning payloads persist canonical bytes and typed evidence`() {
    val harness = checkpointHarness()
    val malformedShared = validShared(payload = validShared().preplanPayload + "}")
    val malformedPlan = validPlan(payload = validPlan().planPayload + "}")

    harness.checkpoint.checkpointSharedPreplan(malformedShared, harness.dbOverride)
    harness.checkpoint.checkpointSubtaskPlan(malformedPlan, harness.dbOverride)

    val storedShared = requireNotNull(harness.readShared())
    val storedPlan = requireNotNull(harness.readPlan())
    assertNotNull(storedShared.repairEvidence)
    assertNotNull(storedPlan.repairEvidence)
    assertEquals(validShared().preplanPayload, storedShared.preplanPayload)
    assertEquals(validPlan().planPayload, storedPlan.planPayload)
    assertEquals(sha256HexUtf8(storedShared.preplanPayload), storedShared.payloadSha256)
    assertEquals(sha256HexUtf8(storedPlan.planPayload), storedPlan.payloadSha256)
    assertEquals(sha256HexUtf8(malformedShared.preplanPayload), storedShared.repairEvidence?.originalDigest)
    assertEquals(sha256HexUtf8(malformedPlan.planPayload), storedPlan.repairEvidence?.originalDigest)
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
      storedShared.repairEvidence?.operation,
    )
    assertEquals(
      FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER,
      storedPlan.repairEvidence?.operation,
    )
    assertEquals(sha256HexUtf8(validShared().preplanPayload), storedShared.repairEvidence?.repairedDigest)
    assertEquals(sha256HexUtf8(validPlan().planPayload), storedPlan.repairEvidence?.repairedDigest)
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
    val shared = validShared(payload = payloadJson(phaseId = "preplan", contractVersion = "9.9"))

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
    val plan = validPlan(payload = payloadJson(phaseId = "plan", producedOutputsJson = "{}"))

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

  @Test
  fun `a stored preplan failing its projection contract reads as regenerable rather than as a fatal read`() {
    val harness = checkpointHarness()
    // Written before the projection gate existed: schema-valid phase output, no bounded projection.
    harness.storeRawShared(
      validShared(payload = payloadJson("preplan", producedOutputsJson = """{"notes":"legacy"}""")),
    )

    assertNull(harness.checkpoint.findSharedPreplan(identity(), harness.dbOverride))
  }

  @Test
  fun `a stored subtask plan failing its projection contract reads as regenerable rather than as a fatal read`() {
    val harness = checkpointHarness().withShared()
    harness.storeRawPlan(
      validPlan(payload = payloadJson("plan", producedOutputsJson = MISSING_VALUE_PLAN_PROJECTION)),
    )

    val recovered = harness.checkpoint.findSubtaskPlan(
      identity(),
      subtaskId = 1,
      governedSubSpecPath = descriptor().governedSubSpecPath,
      dbOverride = harness.dbOverride,
    )

    // Reported missing, so the sweep re-produces it under the gate instead of wedging the goal.
    assertNull(recovered)
  }

  @Test
  fun `regenerating a projection-invalid stored preplan replaces it instead of loud-failing as immutable`() {
    val harness = checkpointHarness()
    harness.storeRawShared(
      validShared(payload = payloadJson("preplan", producedOutputsJson = """{"notes":"legacy"}""")),
    )
    val regenerated = validShared()

    harness.checkpoint.recheckpointSharedPreplan(regenerated, harness.dbOverride)

    assertEquals(regenerated.preplanPayload, harness.readShared()?.preplanPayload)
    val gated = harness.checkpoint.findSharedPreplan(identity(), harness.dbOverride)
    assertEquals(regenerated.preplanPayload, gated?.preplanPayload)
  }

  @Test
  fun `regenerating a projection-invalid stored subtask plan replaces it instead of loud-failing as immutable`() {
    val harness = checkpointHarness().withShared()
    harness.storeRawPlan(
      validPlan(payload = payloadJson("plan", producedOutputsJson = MISSING_VALUE_PLAN_PROJECTION)),
    )
    val regenerated = validPlan()

    harness.checkpoint.recheckpointSubtaskPlan(regenerated, harness.dbOverride)

    assertEquals(regenerated.planPayload, harness.readPlan()?.planPayload)
  }

  @Test
  fun `re-checkpointing a gate-satisfying record with different bytes still loud-fails as immutable`() {
    val harness = checkpointHarness()
    harness.checkpoint.checkpointSharedPreplan(validShared(), harness.dbOverride)
    val otherProjection =
      """{"value":"A different preplan prose payload for refresh testing."}"""
    val different = validShared(payload = payloadJson("preplan", producedOutputsJson = otherProjection))

    assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
      harness.checkpoint.recheckpointSharedPreplan(different, harness.dbOverride)
    }
  }

  @Test
  fun `a stored subtask plan failing its projection contract still exposes its sub-spec hash for recovery`() {
    val harness = checkpointHarness().withShared()
    harness.storeRawPlan(
      validPlan(payload = payloadJson("plan", producedOutputsJson = MISSING_VALUE_PLAN_PROJECTION)),
    )

    val stored = harness.checkpoint.findStoredSubtaskPlan(
      identity(),
      subtaskId = 1,
      governedSubSpecPath = descriptor().governedSubSpecPath,
      dbOverride = harness.dbOverride,
    )

    assertEquals(descriptor().subSpecHash, stored?.subSpecHash)
  }

  @Test
  fun `a goal wedged on legacy projection-invalid records recovers in band across the real store`() {
    // The SKILL-141 wedge end to end: both durable records were written before the projection gate, so
    // recovery reports nothing prepared, the sweep re-produces each under the gate, and the replacement
    // lands. Before the replace path existed, step three threw and the goal could only be repaired by
    // hand-editing the database.
    val harness = checkpointHarness()
    harness.storeRawShared(validShared(payload = payloadJson("preplan", producedOutputsJson = """{"n":"legacy"}""")))
    harness.storeRawPlan(
      validPlan(payload = payloadJson("plan", producedOutputsJson = MISSING_VALUE_PLAN_PROJECTION)),
    )

    val wedged = harness.checkpoint.recoveryProgress(
      identity(),
      listOf(descriptor()),
      provenance(),
      harness.dbOverride,
    )
    assertFalse(wedged.sharedPreplanPrepared, "a projection-invalid preplan must read as not prepared")
    assertEquals(0, wedged.preparedPlanCount)
    assertEquals(1, wedged.firstMissingSubtaskId)

    harness.checkpoint.recheckpointSharedPreplan(validShared(), harness.dbOverride)
    harness.checkpoint.recheckpointSubtaskPlan(validPlan(), harness.dbOverride)

    val recovered = harness.checkpoint.recoveryProgress(
      identity(),
      listOf(descriptor()),
      provenance(),
      harness.dbOverride,
    )
    assertTrue(recovered.sharedPreplanPrepared)
    assertEquals(1, recovered.preparedPlanCount)
    assertNull(recovered.firstMissingSubtaskId, "the goal must be fully prepared again with no operator surgery")
  }

  @Test
  fun `the SKILL-141 escape is refused at the write gate so it can never be checkpointed`() {
    val harness = checkpointHarness().withShared()
    val escape = validPlan(payload = payloadJson("plan", producedOutputsJson = MISSING_VALUE_PLAN_PROJECTION))

    val error = assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      harness.checkpoint.checkpointSubtaskPlan(escape, harness.dbOverride)
    }

    assertContains(error.reason, "value")
    assertNull(harness.readPlan(), "a projection-invalid plan must leave no durable row behind")
  }

  private fun checkpointHarness(): CheckpointHarness {
    val tempDir = Files.createTempDirectory("goal-planning-checkpoint")
    val dbOverride = tempDir.resolve("metrics.db").toString()
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(environment = emptyMap(), userHome = tempDir))
    val checkpoint = GoalPlanningPreparationCheckpoint(
      database = database,
      envelopeValidator = GoalPlanningPreparationEnvelopeValidatorAdapter(),
      phaseOutputValidator = FeatureTaskRuntimePhaseOutputValidatorAdapter(),
      planningProjectionValidator = FeatureTaskRuntimePlanningProjectionValidatorAdapter(),
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

    // Bypasses the write gate to reproduce a record persisted before the gate existed.
    fun storeRawShared(checkpoint: SharedGoalPreplanCheckpoint) {
      database.selfManagedWrite(dbOverride) { it.goalPlanningPreparations.checkpointSharedPreplan(checkpoint) }
    }

    fun storeRawPlan(plan: GoalSubtaskPlanCheckpoint) {
      database.selfManagedWrite(dbOverride) { it.goalPlanningPreparations.checkpointSubtaskPlan(plan) }
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

    // The checkpoint write path now runs the shared producer projection gate, so a prepared payload
    // must carry the real bounded projection its phase owns.
    fun payloadJson(
      phaseId: String,
      contractVersion: String = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      status: String = "completed",
      producedOutputsJson: String = projectionJson(phaseId),
    ): String = """
      {"contract_version":"$contractVersion","phase_id":"$phaseId","status":"$status","summary":"s",
      "produced_outputs":$producedOutputsJson}
    """.trimIndent().replace("\n", "")

    // The SKILL-141 escape shape: a settled plan whose only task carries no test obligations.
    const val MISSING_VALUE_PLAN_PROJECTION = """{"prompt":"optional only"}"""

    fun projectionJson(phaseId: String): String = if (phaseId == "preplan") {
      """{"value":"Producer may omit obligations in prose."}"""
    } else {
      """{"value":"Checkpointed plan prose for downstream implement."}"""
    }
  }
}
