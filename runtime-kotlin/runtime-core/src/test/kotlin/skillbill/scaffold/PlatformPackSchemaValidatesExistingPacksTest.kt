package skillbill.scaffold

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SKILL-47 AC5: every shipped `platform-packs/<slug>/platform.yaml` MUST
 * validate against the canonical schema. This test pins the schema's
 * correctness BEFORE the loader is refactored to consume it, so we can
 * prove the schema captures today's manifests faithfully.
 */
class PlatformPackSchemaValidatesExistingPacksTest {
  @Test
  fun `canonical schema validates kotlin and kmp platform_yaml`() {
    val repoRoot = repoRootFromTest()
    val schema = loadCanonicalSchema(repoRoot)

    val packs = listOf(
      repoRoot.resolve("platform-packs/kotlin/platform.yaml"),
      repoRoot.resolve("platform-packs/kmp/platform.yaml"),
    )
    packs.forEach { packPath ->
      assertTrue(Files.isRegularFile(packPath), "Missing manifest at $packPath.")
      val instance = YAMLMapper().readTree(Files.readString(packPath))
      val errors = schema.validate(instance)
      assertTrue(
        errors.isEmpty(),
        "Schema rejected $packPath:\n${errors.joinToString("\n") { error -> " - $error" }}",
      )
    }
  }
}

private fun loadCanonicalSchema(repoRoot: Path): JsonSchema {
  val schemaFile = repoRoot.resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)
  // Read as YAML, re-emit as JSON so the networknt validator gets a clean JSON tree regardless
  // of how the schema file is authored.
  val yaml = YAMLMapper().readTree(Files.readString(schemaFile))
  val jsonText = ObjectMapper().writeValueAsString(yaml)
  val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
  return factory.getSchema(jsonText)
}
