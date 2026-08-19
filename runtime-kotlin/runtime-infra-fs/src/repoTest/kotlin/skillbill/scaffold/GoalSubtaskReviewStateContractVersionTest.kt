package skillbill.scaffold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.GoalSubtaskReviewStateSchemaPaths
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SKILL-142 AC-018: pins `contract_version` parity between the canonical
 * schema file (`orchestration/contracts/goal-subtask-review-state-schema.yaml`)
 * and `GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION`. Bumping one without the
 * other is a build break, by design.
 *
 * Also validates that the pass-accounting fields declare no upper bound, so the
 * remediation loop terminates on the Blocker disposition rather than a ceiling.
 */
class GoalSubtaskReviewStateContractVersionTest {
  @Test
  fun `schema contract_version const matches GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION`() {
    val schemaFile = repoRootFromTest().resolve(GoalSubtaskReviewStateSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")
    assertNotNull(
      contractVersionNode.takeIf { !it.isMissingNode && it.isTextual },
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal " +
        "GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION ($GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `pass accounting declares a positive lower bound and no upper bound`() {
    val schemaFile = repoRootFromTest().resolve(GoalSubtaskReviewStateSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val properties = schema.path("properties")
    mapOf(
      "reserved_pass_number" to 1,
      "completed_pass_count" to 0,
      "emitted_pass_count" to 0,
    ).forEach { (field, minimum) ->
      val property = properties.path(field)
      assertTrue(
        property.path("maximum").isMissingNode,
        "$field must declare no maximum; a ceiling would re-impose the bounded remediation loop.",
      )
      assertEquals(minimum, property.path("minimum").asInt(), "$field.minimum must be $minimum.")
    }

    val passResults = properties.path("pass_results")
    assertTrue(passResults.path("maxItems").isMissingNode, "pass_results must accept unboundedly many passes.")
    assertTrue(
      passResults.path("items").path("properties").path("pass_number").path("maximum").isMissingNode,
      "pass_number must accept any positive pass.",
    )
    assertEquals(
      1,
      passResults.path("items").path("properties").path("pass_number").path("minimum").asInt(),
      "pass_number must stay a positive integer.",
    )
  }
}
