package skillbill.contracts.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.workflow.taskruntime.model.AUDIT_GENERATION_CONTRACT_VERSION
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditGenerationSchemaContractVersionTest {
  @Test
  fun `canonical repository schema pins its identity and contract version to the runtime constants`() {
    val schemaFile = repositorySchemaFile()
    assertTrue(
      Files.isRegularFile(schemaFile),
      "canonical schema must exist at ${FeatureTaskRuntimeAuditGenerationSchemaPaths.REPO_RELATIVE_PATH}",
    )
    val schema = YAMLMapper().readTree(Files.readString(schemaFile))

    assertEquals(
      FeatureTaskRuntimeAuditGenerationSchemaPaths.EXPECTED_SCHEMA_ID,
      schema.path("\$id").asText(),
    )
    assertEquals(FEATURE_TASK_RUNTIME_AUDIT_GENERATION_CONTRACT_VERSION, contractVersionOf(schema))
    assertEquals(AUDIT_GENERATION_CONTRACT_VERSION, contractVersionOf(schema))
  }

  @Test
  fun `bundled classpath schema matches the canonical repository schema`() {
    val stream = checkNotNull(
      javaClass.classLoader.getResourceAsStream(
        FeatureTaskRuntimeAuditGenerationSchemaPaths.CLASSPATH_RESOURCE,
      ),
    )
    val bundled = stream.use { YAMLMapper().readTree(it) }

    assertEquals(YAMLMapper().readTree(Files.readString(repositorySchemaFile())), bundled)
  }

  private fun contractVersionOf(schema: JsonNode): String =
    schema.path("properties").path("contract_version").path("const").asText()

  private fun repositorySchemaFile(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(FeatureTaskRuntimeAuditGenerationSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("canonical audit-generation schema not found walking up from ${Path.of("").toAbsolutePath()}")
  }
}
