package skillbill.scaffold

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.workflow.WorkflowStateSchemaPaths
import skillbill.workflow.assertWorkflowStateSchemaIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * SKILL-48 cleanup acceptance tests covering C2, C4, C7, and C8. Each test below pins one
 * acceptance criterion so a regression that drops or weakens the cleanup fails loudly.
 */
class PlatformPackSchemaCleanupTest {

  // -----------------------------------------------------------------------
  // C7 — schema identity assertion (loaded $id and contract_version.const)
  // -----------------------------------------------------------------------

  @Test
  fun `C7 classpath shadow with mismatched schema id loud-fails`() {
    val mismatchedIdYaml = """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "https://malicious.example/shadow-schema.yaml"
      type: object
      properties:
        contract_version:
          const: "$SHELL_CONTRACT_VERSION"
    """.trimIndent()
    val node = YAMLMapper().readTree(mismatchedIdYaml)

    val error = assertFailsWith<InvalidManifestSchemaError> { assertSchemaIdentity(node) }
    val message = error.message.orEmpty()
    assertContains(message, "https://malicious.example/shadow-schema.yaml")
    assertContains(message, PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID)
  }

  @Test
  fun `C7 classpath shadow with mismatched contract_version const loud-fails`() {
    val mismatchedConstYaml = """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "${PlatformPackSchemaPaths.EXPECTED_SCHEMA_ID}"
      type: object
      properties:
        contract_version:
          const: "9.99"
    """.trimIndent()
    val node = YAMLMapper().readTree(mismatchedConstYaml)

    val error = assertFailsWith<InvalidManifestSchemaError> { assertSchemaIdentity(node) }
    val message = error.message.orEmpty()
    assertContains(message, "9.99")
    assertContains(message, SHELL_CONTRACT_VERSION)
  }

  @Test
  fun `C7 canonical schema on disk passes identity assertion`() {
    // Pin the happy path: the bundled canonical schema MUST satisfy both halves of the
    // assertion. If the schema is reshape mid-flight this catches the drift immediately.
    val schemaPath: Path = skillbill.testing.repoRootFromTest()
      .resolve(PlatformPackSchemaPaths.REPO_RELATIVE_PATH)
    val node = YAMLMapper().readTree(Files.readString(schemaPath))
    // Must not throw.
    assertSchemaIdentity(node)
  }

  // -----------------------------------------------------------------------
  // SKILL-48 Subtask 2a — workflow-state schema classpath-shadow guard
  // -----------------------------------------------------------------------

  @Test
  fun `workflow-state schema classpath shadow with mismatched id loud-fails`() {
    val mismatchedIdYaml = """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "https://malicious.example/shadow-workflow-state.yaml"
      type: object
      properties:
        contract_version:
          const: "$WORKFLOW_STATE_CONTRACT_VERSION"
    """.trimIndent()
    val node = YAMLMapper().readTree(mismatchedIdYaml)

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> { assertWorkflowStateSchemaIdentity(node) }
    val message = error.message.orEmpty()
    assertContains(message, "https://malicious.example/shadow-workflow-state.yaml")
    assertContains(message, WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID)
  }

  @Test
  fun `workflow-state schema classpath shadow with mismatched contract_version const loud-fails`() {
    val mismatchedConstYaml = """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "${WorkflowStateSchemaPaths.EXPECTED_SCHEMA_ID}"
      type: object
      properties:
        contract_version:
          const: "9.99"
    """.trimIndent()
    val node = YAMLMapper().readTree(mismatchedConstYaml)

    val error = assertFailsWith<InvalidWorkflowStateSchemaError> { assertWorkflowStateSchemaIdentity(node) }
    val message = error.message.orEmpty()
    assertContains(message, "9.99")
    assertContains(message, WORKFLOW_STATE_CONTRACT_VERSION)
  }

  @Test
  fun `workflow-state canonical schema on disk passes identity assertion`() {
    val schemaPath: Path = skillbill.testing.repoRootFromTest()
      .resolve(WorkflowStateSchemaPaths.REPO_RELATIVE_PATH)
    val node = YAMLMapper().readTree(Files.readString(schemaPath))
    // Must not throw.
    assertWorkflowStateSchemaIdentity(node)
  }

  // -----------------------------------------------------------------------
  // C4 — declared_code_review_areas uniqueItems
  // -----------------------------------------------------------------------

  @Test
  fun `C4 duplicate entries in declared_code_review_areas loud-fail through canonical validator`() {
    val manifest = """
      platform: scenarioslug
      contract_version: "$SHELL_CONTRACT_VERSION"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
        - architecture
    """.trimIndent()

    val tempDir = Files.createTempDirectory("skillbill-c4-uniqueitems-")
    val packRoot = tempDir.resolve("scenarioslug")
    Files.createDirectories(packRoot)
    Files.writeString(packRoot.resolve("platform.yaml"), manifest)

    val error = assertFailsWith<InvalidManifestSchemaError> { loadPlatformManifest(packRoot) }
    val message = error.message.orEmpty()
    assertContains(message, "declared_code_review_areas")
  }

  // -----------------------------------------------------------------------
  // C8 — shared repoRootFromTest() helper, no private copies
  // -----------------------------------------------------------------------

  @Test
  fun `C8 no migrated test still declares a private repoRootFromTest function`() {
    val repoRoot = skillbill.testing.repoRootFromTest()
    val migratedSources = listOf(
      repoRoot.resolve(
        "runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/" +
          "PlatformPackSchemaContractVersionTest.kt",
      ),
      repoRoot.resolve(
        "runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/" +
          "PlatformPackSchemaValidatesExistingPacksTest.kt",
      ),
      repoRoot.resolve(
        "runtime-kotlin/runtime-core/src/test/kotlin/skillbill/scaffold/" +
          "ShellContentLoaderParityTest.kt",
      ),
      repoRoot.resolve(
        "runtime-kotlin/runtime-desktop/core/data/src/jvmTest/kotlin/skillbill/desktop/core/data/" +
          "service/RuntimeRepoBrowserContractsGroupTest.kt",
      ),
      repoRoot.resolve(
        "runtime-kotlin/runtime-desktop/feature/skillbill/src/jvmTest/kotlin/skillbill/desktop/" +
          "feature/skillbill/state/PlatformPackSchemaViewerStateTest.kt",
      ),
    )
    migratedSources.forEach { source ->
      val text = Files.readString(source)
      // Allow imports of the shared helper but reject any private/local re-declaration.
      val redeclared = Regex("""fun\s+repoRootFromTest\s*\(""").containsMatchIn(text)
      check(!redeclared) {
        "SKILL-48 C8 regression: $source still declares a local repoRootFromTest function."
      }
      // C8 also rejects the near-identical `repoRoot()` helper that ShellContentLoaderParityTest
      // previously shipped — same logic, different name, but covered by the SKILL-48 cleanup intent.
      val nearIdenticalRedeclared = Regex("""private\s+fun\s+repoRoot\s*\(""").containsMatchIn(text)
      check(!nearIdenticalRedeclared) {
        "SKILL-48 C8 regression: $source still declares a private repoRoot() helper that duplicates " +
          "skillbill.testing.repoRootFromTest()."
      }
    }
  }

  // -----------------------------------------------------------------------
  // C2 — PlatformPackSchemaValidator.validate signature is Map<String, Any?>
  // -----------------------------------------------------------------------

  @Test
  fun `C2 validate accepts Map of String to Any-question for a well-formed manifest`() {
    // SKILL-48 C2: the tightened signature is `(parsedYaml: Map<String, Any?>, slug: String)`.
    // This test exercises that signature with a syntactically valid manifest and confirms the
    // validator returns without throwing. We deliberately pin the parameter type via a typed
    // local so a future signature regression breaks compilation here.
    val typedManifest: Map<String, Any?> = mapOf(
      "platform" to "scenarioslug",
      "contract_version" to SHELL_CONTRACT_VERSION,
      "routing_signals" to mapOf("strong" to listOf(".kt")),
      "declared_code_review_areas" to emptyList<String>(),
    )
    val validator: PlatformPackSchemaValidator = CanonicalPlatformPackSchemaValidator()
    validator.validate(typedManifest, "scenarioslug")
  }
}
