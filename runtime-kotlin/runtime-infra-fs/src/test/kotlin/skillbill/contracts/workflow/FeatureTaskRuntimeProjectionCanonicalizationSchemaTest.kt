@file:Suppress("MaxLineLength")

package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionKind
import skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FeatureTaskRuntimeProjectionCanonicalizationSchemaTest {
  private val validator = FeatureTaskRuntimePlanningProjectionValidatorAdapter()

  @Test
  fun `an unknown key on a closed receipt object is absorbed and the projection advances`() {
    val receipt = assertIs<FeatureTaskRuntimeImplementationReceipt>(
      parseReceipt(
        """{"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
          """"completed_task_ids":["task-1"],"changed_paths":["src/Foo.kt"],""" +
          """"tests_executed":[{"name":"FooTest.kt","outcome":"passed"}],""" +
          """"reconciliation_evidence":{"reconciled":true,"evidence":"ok"},""" +
          """"bogus":1}""",
      ),
    )

    assertEquals(listOf("task-1"), receipt.completedTaskIds)
  }

  @Test
  fun `a missing required field rejects`() {
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parseReceipt(
        """{"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
          """"completed_task_ids":["task-1"],"changed_paths":["src/Foo.kt"],""" +
          """"tests_executed":[{"name":"FooTest.kt","outcome":"passed"}]}""",
      )
    }
  }

  @Test
  fun `a deviation note that is a pasted JSON body still rejects on the anti-paste pattern`() {
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parseReceipt(
        """{"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
          """"completed_task_ids":["task-1"],"changed_paths":["src/Foo.kt"],""" +
          """"tests_executed":[{"name":"FooTest.kt","outcome":"passed"}],""" +
          """"deviations":[{"ref":"AC-001","note":"{\"phase_id\": \"plan\"}"}],""" +
          """"reconciliation_evidence":{"reconciled":true,"evidence":"ok"}}""",
      )
    }
  }

  @Test
  fun `a budget overflow rejects`() {
    val deviations = (1..FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT + 1).joinToString(",") {
      """{"ref":"AC-$it","note":"note $it"}"""
    }
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parseReceipt(
        """{"projection_kind":"implementation_receipt","contract_version":"0.2",""" +
          """"completed_task_ids":["task-1"],"changed_paths":["src/Foo.kt"],""" +
          """"tests_executed":[{"name":"FooTest.kt","outcome":"passed"}],""" +
          """"deviations":[$deviations],""" +
          """"reconciliation_evidence":{"reconciled":true,"evidence":"ok"}}""",
      )
    }
  }

  private fun parseReceipt(producedOutputs: String) = featureTaskRuntimePlanningProjectionFromEnvelope(
    envelope = envelope(producedOutputs),
    producingPhaseId = "implement",
    expectedKind = FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT,
    schemaValidator = validator,
  )

  @Suppress("UNCHECKED_CAST")
  private fun envelope(producedOutputs: String): Map<String, Any?> = mapOf(
    "produced_outputs" to (
      skillbill.contracts.JsonSupport.jsonElementToValue(
        requireNotNull(skillbill.contracts.JsonSupport.parseObjectOrNull(producedOutputs)),
      ) as Map<String, Any?>
      ),
  )
}
