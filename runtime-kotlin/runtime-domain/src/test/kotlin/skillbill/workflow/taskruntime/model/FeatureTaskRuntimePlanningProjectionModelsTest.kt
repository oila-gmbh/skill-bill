@file:Suppress("MaxLineLength")

package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.workflow.NoopFeatureTaskRuntimePlanningProjectionValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePlanningProjectionModelsTest {
  @Test
  fun `executable plan rejects a cyclic task dependency graph`() {
    val error = assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeExecutablePlan(
        mode = FeatureTaskRuntimePlanMode.DIRECT,
        tasks = listOf(
          FeatureTaskRuntimePlanTask("task-a", listOf("task-b"), "a", listOf("AC-001"), emptyList(), listOf("parity")),
          FeatureTaskRuntimePlanTask("task-b", listOf("task-a"), "b", listOf("AC-002"), emptyList(), listOf("parity")),
        ),
        validationStrategy = listOf("focused gradle"),
      )
    }
    assertTrue(error.message!!.contains("acyclic"))
  }

  @Test
  fun `executable plan rejects a dependency on an undeclared task`() {
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      FeatureTaskRuntimeExecutablePlan(
        mode = FeatureTaskRuntimePlanMode.DIRECT,
        tasks = listOf(
          FeatureTaskRuntimePlanTask(
            "task-a",
            listOf("task-ghost"),
            "a",
            listOf("AC-001"),
            emptyList(),
            listOf("parity"),
          ),
        ),
        validationStrategy = listOf("focused gradle"),
      )
    }
  }

  @Test
  fun `executable plan rejects duplicate task ids`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeExecutablePlan(
        mode = FeatureTaskRuntimePlanMode.DIRECT,
        tasks = listOf(
          FeatureTaskRuntimePlanTask(
            "task-a",
            description = "a",
            criterionRefs = listOf("AC-001"),
            testObligations = listOf("t"),
          ),
          FeatureTaskRuntimePlanTask(
            "task-a",
            description = "a2",
            criterionRefs = listOf("AC-002"),
            testObligations = listOf("t"),
          ),
        ),
        validationStrategy = listOf("focused gradle"),
      )
    }
  }

  @Test
  fun `receipt rejects absolute paths, backslashes, dotdot segments, and duplicates`() {
    val baseCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "abc")
    val baseReconciliation = FeatureTaskRuntimeReconciliationEvidence(reconciled = true, evidence = "ok")
    val baseExecuted = listOf(FeatureTaskRuntimeTestExecution("FooTest.kt", FeatureTaskRuntimeTestOutcome.PASSED))

    assertFailsWith<IllegalArgumentException> {
      receipt(
        changedPaths = listOf("/abs/path.kt"),
        checkpoint = baseCheckpoint,
        reconciliation = baseReconciliation,
        executed = baseExecuted,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      receipt(
        changedPaths = listOf("src\\back.kt"),
        checkpoint = baseCheckpoint,
        reconciliation = baseReconciliation,
        executed = baseExecuted,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      receipt(
        changedPaths = listOf("../escape.kt"),
        checkpoint = baseCheckpoint,
        reconciliation = baseReconciliation,
        executed = baseExecuted,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      receipt(
        changedPaths = listOf("src/A.kt", "src/A.kt"),
        checkpoint = baseCheckpoint,
        reconciliation = baseReconciliation,
        executed = baseExecuted,
      )
    }
  }

  @Test
  fun `receipt requires reconciled true and rejects a false claim`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeReconciliationEvidence(reconciled = false, evidence = "nope")
    }
  }

  @Test
  fun `receipt parses from produced_outputs and projects the declared field set`() {
    val produced = linkedMapOf<String, Any?>(
      "projection_kind" to "implementation_receipt",
      "contract_version" to FeatureTaskRuntimePlanningProjectionContract.VERSION,
      "completed_task_ids" to listOf("task-01"),
      "changed_paths" to listOf("runtime-domain/model/X.kt"),
      "tests_executed" to listOf(linkedMapOf("name" to "XTest.kt", "outcome" to "passed")),
      "reconciliation_evidence" to linkedMapOf("reconciled" to true, "evidence" to "files at target"),
      "repository_checkpoint" to linkedMapOf("fingerprint" to "abc123"),
    )
    val parsed = assertIs<FeatureTaskRuntimeImplementationReceipt>(
      featureTaskRuntimePlanningProjectionFromEnvelope(
        envelope = linkedMapOf("produced_outputs" to produced),
        producingPhaseId = "implement",
        expectedKind = FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT,
        schemaValidator = NoopFeatureTaskRuntimePlanningProjectionValidator,
      ),
    )

    assertEquals(listOf("task-01"), parsed.completedTaskIds)
    val fieldNames = parsed.toProjectionFields().map { it.name }
    assertEquals(FeatureTaskRuntimeImplementationReceipt.DECLARED_FIELD_NAMES, fieldNames)
  }

  @Test
  fun `an unknown projection kind is rejected at the parse seam`() {
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      featureTaskRuntimePlanningProjectionFromEnvelope(
        envelope = linkedMapOf("produced_outputs" to linkedMapOf("projection_kind" to "whole_envelope")),
        producingPhaseId = "plan",
        expectedKind = FeatureTaskRuntimeProjectionKind.EXECUTABLE_PLAN,
        schemaValidator = NoopFeatureTaskRuntimePlanningProjectionValidator,
      )
    }
  }

  @Test
  fun `executable plan renders the executable projection fields and never the decomposition package`() {
    val plan = FeatureTaskRuntimeExecutablePlan(
      mode = FeatureTaskRuntimePlanMode.DIRECT,
      tasks = listOf(
        FeatureTaskRuntimePlanTask(
          "task-01",
          description = "add contract",
          criterionRefs = listOf("AC-005"),
          testObligations = listOf("parity"),
        ),
      ),
      validationStrategy = listOf("focused gradle"),
    )
    val fields = plan.toProjectionFields().map { it.name }
    assertEquals(FeatureTaskRuntimeExecutablePlan.DECLARED_FIELD_NAMES, fields)
    assertTrue(fields.none { it.contains("decomposition") })
  }

  @Test
  fun `toPlanCommitment yields a bounded obligation-only subset`() {
    val plan = FeatureTaskRuntimeExecutablePlan(
      mode = FeatureTaskRuntimePlanMode.DIRECT,
      tasks = listOf(
        FeatureTaskRuntimePlanTask(
          "task-01",
          description = "add contract",
          criterionRefs = listOf("AC-005"),
          testObligations = listOf("parity"),
        ),
      ),
      validationStrategy = listOf("focused gradle"),
    )
    val commitment = plan.toPlanCommitment()
    assertEquals(listOf("task_commitments"), commitment.toProjectionFields().map { it.name })
    assertEquals("task-01", commitment.taskCommitments.single().taskId)
  }

  @Test
  fun `the producing-phase mapping names the three producer kinds and nothing else`() {
    assertEquals(
      FeatureTaskRuntimeProjectionKind.PREPLANNING_DIGEST,
      FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor("preplan"),
    )
    assertEquals(
      FeatureTaskRuntimeProjectionKind.EXECUTABLE_PLAN,
      FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor("plan"),
    )
    assertEquals(
      FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT,
      FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor("implement"),
    )
    // plan_commitment is derived from the executable plan, never produced, so no phase maps to it.
    listOf("audit", "implement_fix", "review", "validate", "write_history", "commit_push", "pr").forEach { phaseId ->
      assertNull(
        FeatureTaskRuntimePlanningProjectionContract.producedProjectionKindFor(phaseId),
        "phase '$phaseId' produces no planning projection and must not be gated against one",
      )
    }
  }

  @Test
  fun `a decompose package is distinguished from an executable plan that merely declares decompose mode`() {
    // A decompose stop terminates at planning, so the package is never parsed as an executable plan;
    // a projection carrying projection_kind stays under the producer gate whatever its mode.
    assertTrue(
      featureTaskRuntimeIsDecompositionPackage(
        mapOf("produced_outputs" to mapOf("mode" to "decompose", "subtasks" to listOf<Any>())),
      ),
    )
    assertFalse(
      featureTaskRuntimeIsDecompositionPackage(
        mapOf("produced_outputs" to mapOf("projection_kind" to "executable_plan", "mode" to "decompose")),
      ),
    )
    assertFalse(
      featureTaskRuntimeIsDecompositionPackage(mapOf("produced_outputs" to mapOf("mode" to "direct"))),
    )
    // A free-form plan output claiming neither must stay gated, or AC-001 has a hole.
    assertFalse(featureTaskRuntimeIsDecompositionPackage(mapOf("produced_outputs" to mapOf("steps" to listOf("x")))))
  }

  @Test
  fun `a plan with an uppercase task id and matching depends_on canonicalizes both and parses to the canonical ids`() {
    val plan = executablePlanMap(taskId = "T1", secondTaskId = "Task_2", dependsOn = listOf("T1"))

    val parsed = assertIs<FeatureTaskRuntimeExecutablePlan>(
      featureTaskRuntimePlanningProjectionFromEnvelope(
        envelope = linkedMapOf("produced_outputs" to plan),
        producingPhaseId = "plan",
        expectedKind = FeatureTaskRuntimeProjectionKind.EXECUTABLE_PLAN,
        schemaValidator = NoopFeatureTaskRuntimePlanningProjectionValidator,
      ),
    )

    assertEquals(listOf("t1", "task-2"), parsed.tasks.map { it.taskId })
    assertEquals(listOf("t1"), parsed.tasks[1].dependsOn, "the reference canonicalizes to the declared canonical id")
  }

  @Test
  fun `the canonicalized map is the value passed to the schema validator and to the variant constructor`() {
    val recording = RecordingSchemaValidator()

    val parsed = assertIs<FeatureTaskRuntimeExecutablePlan>(
      featureTaskRuntimePlanningProjectionFromEnvelope(
        envelope = linkedMapOf("produced_outputs" to executablePlanMap(taskId = "T1")),
        producingPhaseId = "plan",
        expectedKind = FeatureTaskRuntimeProjectionKind.EXECUTABLE_PLAN,
        schemaValidator = recording,
      ),
    )

    val validatedTasks = recording.captured.single()["tasks"] as List<*>
    assertEquals("t1", (validatedTasks.single() as Map<*, *>)["task_id"], "the validator must see the canonical map")
    assertEquals("t1", parsed.tasks.single().taskId, "fromMap must build from the same canonical map")
  }

  private fun executablePlanMap(
    taskId: String,
    secondTaskId: String? = null,
    dependsOn: List<String> = emptyList(),
  ): Map<String, Any?> {
    fun task(id: String, deps: List<String>) = linkedMapOf<String, Any?>(
      "task_id" to id,
      "depends_on" to deps,
      "description" to "add contract",
      "criterion_refs" to listOf("AC-005"),
      "test_obligations" to listOf("parity"),
    )
    val tasks = buildList {
      add(task(taskId, emptyList()))
      if (secondTaskId != null) add(task(secondTaskId, dependsOn))
    }
    return linkedMapOf(
      "projection_kind" to "executable_plan",
      "contract_version" to FeatureTaskRuntimePlanningProjectionContract.VERSION,
      "mode" to "direct",
      "tasks" to tasks,
      "validation_strategy" to listOf("focused gradle"),
    )
  }

  private fun receipt(
    changedPaths: List<String>,
    checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
    reconciliation: FeatureTaskRuntimeReconciliationEvidence,
    executed: List<FeatureTaskRuntimeTestExecution>,
  ) = FeatureTaskRuntimeImplementationReceipt(
    completedTaskIds = listOf("task-01"),
    changedPaths = changedPaths,
    testsExecuted = executed,
    reconciliationEvidence = reconciliation,
    repositoryCheckpoint = checkpoint,
  )
}

/** Captures the exact wire map handed to the schema gate so a test can prove it is the canonical map. */
private class RecordingSchemaValidator : skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator {
  val captured = mutableListOf<Map<String, Any?>>()

  override fun validatePlanningProjection(producedOutputs: Map<String, Any?>, sourceLabel: String) {
    captured += producedOutputs
  }
}
