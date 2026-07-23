@file:Suppress("MaxLineLength")

package skillbill.workflow.taskruntime.model

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    val parsed = featureTaskRuntimePlanningProjectionFromEnvelope(
      envelope = linkedMapOf("produced_outputs" to produced),
      producingPhaseId = "implement",
    ) as FeatureTaskRuntimeImplementationReceipt

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
