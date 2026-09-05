package skillbill.contracts.workflow

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalPortableReviewBaselineSchemaContractVersionTest {
  @Test
  fun `schema contract version and id match the Kotlin contract`() {
    val schema = classpathSchema()
    val version = schema.path("properties").path("contract_version").path("const")

    assertTrue(version.isTextual, "Portable review baseline schema must pin a string contract_version const.")
    assertEquals(GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION, version.asText())
    assertEquals(GoalPortableReviewBaselineSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  private fun classpathSchema() = GoalPortableReviewBaselineSchemaContractVersionTest::class.java.classLoader
    .getResourceAsStream(GoalPortableReviewBaselineSchemaPaths.CLASSPATH_RESOURCE)
    .let { stream ->
      assertNotNull(stream, "Portable review baseline schema is missing from the classpath.")
      stream.use { YAMLMapper().readTree(it.readBytes().toString(Charsets.UTF_8)) }
    }
}
