package skillbill.contracts.workflow

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalSubtaskReviewStateSchemaContractVersionTest {
  @Test
  fun `schema contract version and id match the Kotlin contract`() {
    val schema = classpathSchema()
    val version = schema.path("properties").path("contract_version").path("const")

    assertTrue(version.isTextual, "Goal-subtask review state schema must pin a string contract_version const.")
    assertEquals(GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION, version.asText())
    assertEquals(GoalSubtaskReviewStateSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  private fun classpathSchema() = GoalSubtaskReviewStateSchemaContractVersionTest::class.java.classLoader
    .getResourceAsStream(GoalSubtaskReviewStateSchemaPaths.CLASSPATH_RESOURCE)
    .let { stream ->
      assertNotNull(stream, "Goal-subtask review state schema is missing from the classpath.")
      stream.use { YAMLMapper().readTree(it.readBytes().toString(Charsets.UTF_8)) }
    }
}
