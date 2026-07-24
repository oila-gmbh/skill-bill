@file:Suppress("MaxLineLength")

package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError
import skillbill.infrastructure.fs.FeatureTaskRuntimePlanningProjectionValidatorAdapter
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeExecutablePlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionKind
import skillbill.workflow.taskruntime.model.featureTaskRuntimePlanningProjectionFromEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * SKILL-140 subtask 2: canonicalization proven against the ENFORCED Draft 2020-12 schema (the real
 * infra-fs adapter, not the Noop stand-in), so acceptance and rejection reflect the contract callers
 * actually face. Canonical trivia is absorbed before validation; structural violations and the
 * anti-paste patterns reject exactly as before, with unchanged error typing.
 */
class FeatureTaskRuntimeProjectionCanonicalizationSchemaTest {
  private val validator = FeatureTaskRuntimePlanningProjectionValidatorAdapter()

  // --- AC-001: canonical ids accepted; the parsed projection carries the canonical ids ----------

  @Test
  fun `a plan whose task id and matching depends_on are uppercase canonicalizes both and advances`() {
    val plan = assertIs<FeatureTaskRuntimeExecutablePlan>(
      parsePlan(
        """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[""" +
          """{"task_id":"T1","description":"first","criterion_refs":["AC-001"],"test_obligations":["parity"]},""" +
          """{"task_id":"Task_2","depends_on":["T1"],"description":"second","criterion_refs":["AC-002"],""" +
          """"test_obligations":["parity"]}],"validation_strategy":["focused gradle"]}""",
      ),
    )

    assertEquals(listOf("t1", "task-2"), plan.tasks.map { it.taskId })
    assertEquals(listOf("t1"), plan.tasks[1].dependsOn, "the reference must canonicalize to the declared id")
  }

  // --- AC-002: compact-summary normalization vs. the authoritative anti-paste patterns ----------

  @Test
  fun `a description with backticks and tabs is normalized and accepted`() {
    val plan = parsePlan(
      """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[""" +
        """{"task_id":"task-1","description":"call\t`fn()`\tnow","criterion_refs":["AC-001"],""" +
        """"test_obligations":["parity"]}],"validation_strategy":["focused gradle"]}""",
    ) as FeatureTaskRuntimeExecutablePlan

    assertEquals("call fn() now", plan.tasks.single().description)
  }

  @Test
  fun `a description that is a pasted JSON body still rejects on the anti-paste pattern`() {
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parsePlan(
        """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[""" +
          """{"task_id":"task-1","description":"{\"phase_id\": \"plan\", \"status\": \"completed\"}",""" +
          """"criterion_refs":["AC-001"],"test_obligations":["parity"]}],"validation_strategy":["v"]}""",
      )
    }
  }

  @Test
  fun `a description that is a diff hunk still rejects on the anti-paste pattern`() {
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parsePlan(
        """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[""" +
          """{"task_id":"task-1","description":"diff --git a/x b/x","criterion_refs":["AC-001"],""" +
          """"test_obligations":["parity"]}],"validation_strategy":["v"]}""",
      )
    }
  }

  @Test
  fun `a diff paste whose marker is not at line-start still rejects after canonicalization`() {
    // The anti-paste diff markers are `^`-anchored; collapsing the interior line break to a space would
    // slide `diff --git` off line-start where the pattern misses it. The break is preserved, so the
    // no-line-break guard rejects the whole multi-line body.
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parsePlan(
        """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[""" +
          """{"task_id":"task-1","description":"changes:\ndiff --git a/x b/x","criterion_refs":["AC-001"],""" +
          """"test_obligations":["parity"]}],"validation_strategy":["v"]}""",
      )
    }
  }

  @Test
  fun `a description that is a multi-line JSON-array body still rejects after canonicalization`() {
    // A pasted JSON array of strings matches none of the single-line anti-paste markers, so only its
    // line breaks keep it out; collapsing them would flatten it into an accepted single line.
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parsePlan(
        """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[""" +
          """{"task_id":"task-1","description":"[\n\"alpha\",\n\"beta\"\n]","criterion_refs":["AC-001"],""" +
          """"test_obligations":["parity"]}],"validation_strategy":["v"]}""",
      )
    }
  }

  // --- AC-003: structural violations reject as before, with unchanged typing --------------------

  @Test
  fun `a missing required field rejects`() {
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parseDigest(
        """{"projection_kind":"preplanning_digest","contract_version":"0.1","affected_boundaries":["b"],""" +
          """"rollout":{"flag_required":false,"notes":"n"},"validation_strategy":["v"]}""",
      )
    }
  }

  @Test
  fun `an unknown key under additionalProperties false rejects`() {
    val error = assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parseDigest(
        """{"projection_kind":"preplanning_digest","contract_version":"0.1","affected_boundaries":["b"],""" +
          """"risks":["r"],"rollout":{"flag_required":false,"notes":"n"},"validation_strategy":["v"],"bogus":1}""",
      )
    }
    assertEquals(true, error.reason.contains("additionalProperties") || error.reason.contains("bogus"))
  }

  @Test
  fun `a budget overflow rejects`() {
    val boundaries = (1..129).joinToString(",") { "\"b$it\"" }
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parseDigest(
        """{"projection_kind":"preplanning_digest","contract_version":"0.1","affected_boundaries":[$boundaries],""" +
          """"risks":["r"],"rollout":{"flag_required":false,"notes":"n"},"validation_strategy":["v"]}""",
      )
    }
  }

  @Test
  fun `a dependency cycle rejects after canonicalization`() {
    // The cycle is expressed through pre-canonical ids; canonicalization rewrites them referentially, so
    // the acyclicity check still sees — and rejects — the cycle.
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parsePlan(
        """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[""" +
          """{"task_id":"T1","depends_on":["Task_2"],"description":"a","criterion_refs":["AC-001"],""" +
          """"test_obligations":["parity"]},""" +
          """{"task_id":"Task_2","depends_on":["T1"],"description":"b","criterion_refs":["AC-002"],""" +
          """"test_obligations":["parity"]}],"validation_strategy":["v"]}""",
      )
    }
  }

  @Test
  fun `an id that canonicalizes to empty rejects at the schema gate`() {
    assertFailsWith<InvalidFeatureTaskRuntimePlanningProjectionSchemaError> {
      parsePlan(
        """{"projection_kind":"executable_plan","contract_version":"0.1","mode":"direct","tasks":[""" +
          """{"task_id":"!!!","description":"a","criterion_refs":["AC-001"],"test_obligations":["parity"]}],""" +
          """"validation_strategy":["v"]}""",
      )
    }
  }

  private fun parsePlan(producedOutputs: String) = featureTaskRuntimePlanningProjectionFromEnvelope(
    envelope = envelope(producedOutputs),
    producingPhaseId = "plan",
    expectedKind = FeatureTaskRuntimeProjectionKind.EXECUTABLE_PLAN,
    schemaValidator = validator,
  )

  private fun parseDigest(producedOutputs: String) = featureTaskRuntimePlanningProjectionFromEnvelope(
    envelope = envelope(producedOutputs),
    producingPhaseId = "preplan",
    expectedKind = FeatureTaskRuntimeProjectionKind.PREPLANNING_DIGEST,
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
