package skillbill.scaffold

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.scaffold.platformpack.PlatformPackSchemaPaths
import skillbill.scaffold.runtime.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.runtime.SHELL_CONTRACT_VERSION
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SKILL-47 AC2: pins `contract_version` parity between the canonical
 * schema file (`orchestration/contracts/platform-pack-schema.yaml`) and
 * `SHELL_CONTRACT_VERSION`. Bumping one without the other is a build
 * break, by design.
 *
 * F-007: also pins the `$defs/codeReviewArea` enum to
 * `APPROVED_CODE_REVIEW_AREAS`. The enum is now declared once in the
 * schema via `$defs`; this parity test guarantees the schema and the
 * Kotlin set cannot drift in either direction.
 */
class PlatformPackSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches SHELL_CONTRACT_VERSION`() {
    val schemaFile = repoRootFromTest().resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")
    assertNotNull(
      contractVersionNode.takeIf { !it.isMissingNode && it.isTextual },
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      SHELL_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal SHELL_CONTRACT_VERSION ($SHELL_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema defs codeReviewArea enum equals APPROVED_CODE_REVIEW_AREAS`() {
    // F-007: the canonical schema declares the approved-area enum exactly once under
    // `$defs/codeReviewArea`. Every consumer in the schema (`declared_code_review_areas.items`,
    // `declared_files.areas.propertyNames`, `area_metadata.propertyNames`) `$ref`s into this
    // single definition. This parity test fails loudly if either side drifts.
    val schemaFile = repoRootFromTest().resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")

    val schema: JsonNode = YAMLMapper().readTree(Files.readString(schemaFile))
    val enumNode = schema.path("\$defs").path("codeReviewArea").path("enum")
    assertNotNull(
      enumNode.takeIf { !it.isMissingNode && it.isArray },
      "Schema must declare \$defs.codeReviewArea.enum as an array; found: $enumNode",
    )
    val schemaAreas: Set<String> = (0 until enumNode.size())
      .map { index -> enumNode.path(index).asText() }
      .toSet()
    assertEquals(
      APPROVED_CODE_REVIEW_AREAS,
      schemaAreas,
      "Schema \$defs.codeReviewArea.enum must equal APPROVED_CODE_REVIEW_AREAS. " +
        "Schema-only: ${schemaAreas - APPROVED_CODE_REVIEW_AREAS}. " +
        "Kotlin-only: ${APPROVED_CODE_REVIEW_AREAS - schemaAreas}.",
    )
  }
}
