package skillbill.infrastructure.fs.contracts.workflow

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.FeatureTaskRuntimeValidationEvidenceSchemaPaths
import skillbill.contracts.workflow.ValidationEvidencePayloadKeys
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeValidationEvidenceSchemaTest {
  @Test
  fun `schema version and identity stay in parity`() {
    val path = repoRootFromTest().resolve(FeatureTaskRuntimeValidationEvidenceSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(path))
    val schema = YAMLMapper().readTree(Files.readString(path))
    assertEquals(
      FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
      schema.path("properties").path(ValidationEvidencePayloadKeys.CONTRACT_VERSION).path("const").asText(),
    )
    assertEquals(FeatureTaskRuntimeValidationEvidenceSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  @Test
  fun `validator accepts a command and integer exit code`() {
    FeatureTaskRuntimeValidationEvidenceSchemaValidator.validate(
      mapOf(
        ValidationEvidencePayloadKeys.CONTRACT_VERSION to
          FEATURE_TASK_RUNTIME_VALIDATION_EVIDENCE_CONTRACT_VERSION,
        ValidationEvidencePayloadKeys.RESULTS to listOf(
          mapOf(
            ValidationEvidencePayloadKeys.COMMAND to "./gradlew check",
            ValidationEvidencePayloadKeys.EXIT_CODE to 0,
          ),
        ),
      ),
      "test",
    )
  }

  @Test
  fun `schema is available on the classpath`() {
    assertNotNull(
      javaClass.classLoader.getResourceAsStream(
        FeatureTaskRuntimeValidationEvidenceSchemaPaths.CLASSPATH_RESOURCE,
      ),
    )
  }
}
