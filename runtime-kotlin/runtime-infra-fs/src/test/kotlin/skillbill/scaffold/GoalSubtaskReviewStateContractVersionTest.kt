package skillbill.scaffold

import com.fasterxml.jackson.databind.JsonNode
import skillbill.contracts.workflow.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.GoalSubtaskReviewStateSchemaPaths
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
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
 * Also validates that reserved_pass_number and completed_pass_count maxima
 * remain at 2 per the bounded loop contract.
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
      "Schema contract_version.const must equal GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION ($GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `reserved_pass_number and completed_pass_count maxima remain at 2`() {
    val schemaFile = repoRootFromTest().resolve(GoalSubtaskReviewStateSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    listOf(
      "reserved_pass_number",
      "completed_pass_count",
      "emitted_pass_count",
    ).forEach { field ->
      val node: JsonNode = schema.path("properties").path(field).path("maximum")
      assertTrue(
        !node.isMissingNode && node.isIntegralNumber,
        "Schema must declare $field.maximum as an integer; found: $node",
      )
      assertEquals(2, node.asInt(), "$field.maximum must remain 2.")
    }
  }
}
