package skillbill.scaffold

import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidManifestSchemaError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * SKILL-47 AC8 — second bullet: one test per documented violation. Each
 * builds an in-memory `platform.yaml`, runs it through the runtime loader,
 * and asserts the appropriate typed exception AND that the message names
 * the offending field path or value. The schema is the source of shape
 * rules; the coherence-rule cases prove the Kotlin checks documented in
 * `x-coherence-checks` still fire alongside schema validation.
 */
class PlatformPackSchemaViolationsTest {
  @Test
  fun `missing platform field`() {
    val manifest = """
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    // F-003: every loader message is prefixed with "Platform pack '<slug>': ..." so a bare
    // substring of "platform" is unconditional. Tighten to assert the schema validator names
    // the offending field key in quotes AND uses the schema's "required" wording.
    val message = error.message.orEmpty()
    assertContains(message, "'platform'")
    assertContains(message, "required")
  }

  @Test
  fun `missing routing_signals strong`() {
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals: {}
      declared_code_review_areas: []
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    assertContains(error.message.orEmpty(), "routing_signals")
    assertContains(error.message.orEmpty(), "strong")
  }

  @Test
  fun `coherence rule slug parity platform field disagrees with directory name`() {
    // F-004: slug-parity coherence check — manifest 'platform' must equal the parent directory
    // slug. We seed a pack under directory "kotlin" but declare platform: "wrong" inside; the
    // loader must name 'platform' and surface the slug mismatch.
    val manifest = """
      platform: wrong
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("kotlin", manifest)
    }
    val message = error.message.orEmpty()
    assertContains(message, "platform")
    assertContains(message, "wrong")
    assertContains(message, "kotlin")
  }

  @Test
  fun `coherence rule areas without baseline raises named error`() {
    // areas-require-baseline coherence rule: areas mapping is present but
    // declared_files.baseline is missing.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
      declared_files:
        areas:
          architecture: code-review/architecture/content.md
      area_metadata:
        architecture:
          focus: "architecture"
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    assertContains(error.message.orEmpty(), "declared_files")
    assertContains(error.message.orEmpty(), "baseline")
  }

  @Test
  fun `coherence rule area_metadata key not in declared_code_review_areas`() {
    // area-metadata-keys-subset-declared coherence rule: area_metadata
    // contains a key the pack did not declare.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
      declared_files:
        baseline: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
      area_metadata:
        architecture:
          focus: "architecture"
        security:
          focus: "security"
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    assertContains(error.message.orEmpty(), "area_metadata")
    assertContains(error.message.orEmpty(), "security")
  }

  @Test
  fun `declared_code_review_areas with unapproved enum value`() {
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - laravel
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    assertContains(error.message.orEmpty(), "declared_code_review_areas")
    assertContains(error.message.orEmpty(), "laravel")
  }

  @Test
  fun `contract_version mismatch surfaces ContractVersionMismatchError`() {
    val manifest = """
      platform: scenarioslug
      contract_version: "9.99"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
    """.trimIndent()
    val error = assertFailsWith<ContractVersionMismatchError> {
      loadPackThroughContractGate("scenarioslug", manifest)
    }
    val message = error.message.orEmpty()
    // F-010: AC8 requires the message to name BOTH the field and the value.
    assertContains(message, "contract_version")
    assertContains(message, "9.99")
  }

  @Test
  fun `contract_version mismatch surfaces ContractVersionMismatchError from loadPlatformManifest`() {
    // F-009: `loadPlatformManifest` (no explicit contract gate) must also raise the typed
    // error. Previously the validator silently filtered the const failure, so this path
    // accepted any contract_version value.
    val manifest = """
      platform: scenarioslug
      contract_version: "9.99"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
    """.trimIndent()
    val error = assertFailsWith<ContractVersionMismatchError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    val message = error.message.orEmpty()
    assertContains(message, "contract_version")
    assertContains(message, "9.99")
  }

  @Test
  fun `pointer name without md suffix fails schema rule`() {
    // F-005 (a): pointer name must end with `.md`. Use a name that ONLY violates the suffix
    // rule (no `..`, no path separator) so the assertion can pin the specific rule.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        code-review/something:
          - name: "review.txt"
            target: "orchestration/shell-content-contract/shell-ceremony.md"
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    val message = error.message.orEmpty()
    assertContains(message, "review.txt")
    assertContains(message, ".md")
  }

  @Test
  fun `pointer name containing parent-dir sequence fails schema rule`() {
    // F-005 (b): pointer name containing `..` (no path separator). Schema's `not.pattern: \.\.`
    // fires; both the validator and the runtime loader reject the name.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        code-review/something:
          - name: "..md"
            target: "orchestration/shell-content-contract/shell-ceremony.md"
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    val message = error.message.orEmpty()
    assertContains(message, "name")
    assertContains(message, "..")
  }

  @Test
  fun `pointer target containing parent-dir segments fails runtime safety rule`() {
    // F-005 (c): pointer target containing `..` path segments. The schema does not gate the
    // target (it only requires a non-empty string), but the runtime loader's
    // `requireSafePointerTarget` MUST reject the value. We use a syntactically valid pointer
    // `name` so we exercise the runtime-side target check rather than the schema's name rule.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        code-review/something:
          - name: "shell-ceremony.md"
            target: "../../etc/passwd"
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    val message = error.message.orEmpty()
    assertContains(message, "target")
    assertContains(message, "..")
  }

  @Test
  fun `coherence rule areas keys not bijective with declared_code_review_areas`() {
    // areas-equal-declared coherence rule: declared_files.areas has an extra
    // area key that is not in declared_code_review_areas.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
      declared_files:
        baseline: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
          performance: code-review/performance/content.md
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    assertContains(error.message.orEmpty(), "declared_files.areas")
    assertContains(error.message.orEmpty(), "performance")
  }

  @Test
  fun `coherence rule declared area missing from declared_files areas`() {
    // F-006 (reverse-direction): declared_code_review_areas lists an area with NO entry in
    // declared_files.areas. Baseline is provided so the `areas-require-baseline` rule does
    // NOT fire first; the loader must report the missing-area-key bijection violation.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
        - security
      declared_files:
        baseline: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    val message = error.message.orEmpty()
    assertContains(message, "declared_files.areas")
    assertContains(message, "security")
  }

  @Test
  fun `coherence rule pointers unique name per dir rejects duplicate name`() {
    // F-004 (second case): pointers-unique-name-per-dir. The same skill-relative dir has two
    // pointer entries with identical `name` field; the loader must name the duplicate name.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      pointers:
        code-review/something:
          - name: "shell-ceremony.md"
            target: "orchestration/shell-content-contract/shell-ceremony.md"
          - name: "shell-ceremony.md"
            target: "orchestration/shell-content-contract/shell-ceremony.md"
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    val message = error.message.orEmpty()
    assertContains(message, "shell-ceremony.md")
    // Loader's `parsePointers` emits "duplicate pointer entry '<name>' under '<dir>'".
    assertContains(message, "duplicate")
  }

  @Test
  fun `SKILL-48 nested anchored block typo fails loudly`() {
    // Defense-in-depth: a typo *inside* a strict nested anchored block (e.g. mis-spelling
    // `baseline` as `baselin` under `declared_files`) MUST loud-fail with the field path
    // because the nested object keeps `additionalProperties: false`. This is distinct from
    // the top-level anchored typo case below — the schema's nested `additionalProperties:
    // false` is what fires here.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas:
        - architecture
      declared_files:
        baselin: code-review/content.md
        areas:
          architecture: code-review/architecture/content.md
      area_metadata:
        architecture:
          focus: "architecture"
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    val message = error.message.orEmpty()
    // The schema validator names the offending nested field path.
    assertContains(message, "declared_files")
    assertContains(message, "baselin")
  }

  @Test
  fun `SKILL-48 Subtask 3 typo on anchored top-level field fails loudly with field path`() {
    // SKILL-48 A5(b): the top-level `additionalProperties` is now `true` so unknown top-level
    // keys flow through `customFields`. JSON Schema `required` catches typos on REQUIRED
    // anchored fields, but OPTIONAL anchored top-level fields (here: `declared_files`) would
    // otherwise silently fall through to customFields. The Kotlin-side Levenshtein-1 guard
    // in `ShellContentLoader.buildPack` MUST loud-fail with the offending key AND name the
    // suggested anchored field.
    val manifest = """
      platform: scenarioslug
      contract_version: "1.1"
      routing_signals:
        strong: [".kt"]
      declared_code_review_areas: []
      declared_filez:
        baseline: code-review/content.md
    """.trimIndent()
    val error = assertFailsWith<InvalidManifestSchemaError> {
      loadPackFromInMemory("scenarioslug", manifest)
    }
    val message = error.message.orEmpty()
    assertContains(message, "declared_filez")
    assertContains(message, "declared_files")
  }

  // -----------------------------------------------------------------------
  // Harness helpers
  // -----------------------------------------------------------------------

  private fun loadPackFromInMemory(slug: String, manifest: String) {
    val packRoot = newTempPackRoot(slug, manifest)
    // Use loadPlatformManifest so we exercise buildPack + schema validator
    // without triggering the content.md file checks performed by validatePlatformPack.
    loadPlatformManifest(packRoot)
  }

  private fun loadPackThroughContractGate(slug: String, manifest: String) {
    val packRoot = newTempPackRoot(slug, manifest)
    // Goes through validatePlatformPack so contract_version mismatches surface
    // as the dedicated ContractVersionMismatchError.
    loadPlatformPack(packRoot)
  }

  private fun newTempPackRoot(slug: String, manifest: String): Path {
    val tempDir = Files.createTempDirectory("skillbill-platform-pack-schema-test-")
    val packRoot = tempDir.resolve(slug)
    Files.createDirectories(packRoot)
    Files.writeString(packRoot.resolve("platform.yaml"), manifest)
    return packRoot
  }
}
