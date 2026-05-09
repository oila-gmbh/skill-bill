package skillbill.scaffold

import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidDescriptorSectionError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidSkillMdShapeError
import skillbill.error.MissingContentFileError
import skillbill.error.MissingManifestError
import skillbill.error.MissingRequiredSectionError
import skillbill.error.MissingShellCeremonyFileError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShellContentLoaderParityTest {
  @Test
  fun `loads valid pack through manifest driven shell contract`() {
    val pack = loadPlatformPack(fixture("valid_pack"))

    assertEquals("valid_pack", pack.slug)
    assertEquals(SHELL_CONTRACT_VERSION, pack.contractVersion)
    assertEquals(listOf("architecture"), pack.declaredCodeReviewAreas)
    assertEquals(listOf(".fixture"), pack.routingSignals.strong)
    assertEquals("code-review", pack.declaredFiles.baseline.parent.name)
  }

  @Test
  fun `loads optional quality check content without fallback`() {
    val pack = loadPlatformPack(fixture("code_review_and_quality_check"))
    val contentPath = loadQualityCheckContent(pack)

    assertEquals(pack.declaredQualityCheckFile?.resolveSibling("content.md"), contentPath)
    assertTrue(Files.isRegularFile(contentPath))
  }

  @Test
  fun `loud fails with named shell content contract errors`() {
    assertNamedFailure<MissingManifestError>("missing_manifest", "platform.yaml")
    assertNamedFailure<MissingContentFileError>("missing_content_file", "baseline")
    assertNamedFailure<ContractVersionMismatchError>("bad_version", "9.99")
    assertNamedFailure<MissingRequiredSectionError>("missing_section", "## Ceremony")
    assertNamedFailure<InvalidManifestSchemaError>("invalid_schema", "routing_signals")
    assertNamedFailure<InvalidManifestSchemaError>("schema_areas_wrong_type", "declared_code_review_areas")
    assertNamedFailure<InvalidManifestSchemaError>("schema_unapproved_area", "laravel")
    assertNamedFailure<InvalidManifestSchemaError>("extra_area", "performance")
    assertNamedFailure<MissingRequiredSectionError>("heading_in_fence", "## Descriptor")
  }

  @Test
  fun `quality check declaration fails loudly when file or wrapper section is invalid`() {
    val missingFilePack = loadPlatformManifest(fixture("quality_check_missing_file"))
    val missingFileError = assertFailsWith<MissingContentFileError> {
      loadQualityCheckContent(missingFilePack)
    }
    assertContains(missingFileError.message.orEmpty(), "does-not-exist.md")

    val missingSectionPack = loadPlatformManifest(fixture("quality_check_missing_section"))
    val missingSectionError = assertFailsWith<MissingRequiredSectionError> {
      loadQualityCheckContent(missingSectionPack)
    }
    assertContains(missingSectionError.message.orEmpty(), "## Ceremony")
  }

  @Test
  fun `missing shell ceremony fails before descriptor drift`() {
    val fixtureRoot = copyFixture("valid_pack")
    val skillPath = fixtureRoot.resolve("code-review").resolve("SKILL.md")
    Files.writeString(
      skillPath,
      Files.readString(skillPath).replace("Governed skill: `code-review`", "Governed skill: `drifted`"),
    )
    Files.delete(fixtureRoot.resolve("code-review").resolve("shell-ceremony.md"))

    assertFailsWith<MissingShellCeremonyFileError> {
      loadPlatformPack(fixtureRoot)
    }
  }

  @Test
  fun `descriptor drift and invalid skill md shape are distinct named failures`() {
    val driftRoot = copyFixture("valid_pack")
    val driftSkill = driftRoot.resolve("code-review").resolve("SKILL.md")
    Files.writeString(
      driftSkill,
      Files.readString(driftSkill).replace("Governed skill: `code-review`", "Governed skill: `drifted`"),
    )
    val driftError = assertFailsWith<InvalidDescriptorSectionError> {
      loadPlatformPack(driftRoot)
    }
    assertContains(driftError.message.orEmpty(), "## Descriptor")

    // The loader validates both SKILL.md (with body-shape rules) and the sibling content.md
    // (frontmatter only). Remove the required `description` key from content.md to prove the
    // content-side validator path fires.
    val shapeRoot = copyFixture("valid_pack")
    val shapeContent = shapeRoot.resolve("code-review").resolve("content.md")
    Files.writeString(
      shapeContent,
      Files.readString(shapeContent).replace(
        Regex("(?m)^description:.*$"),
        "description:",
      ),
    )
    val shapeError = assertFailsWith<InvalidSkillMdShapeError> {
      loadPlatformPack(shapeRoot)
    }
    assertContains(shapeError.message.orEmpty(), "description")
    // Error path must name content.md, not SKILL.md, so callers can tell which file is broken.
    assertContains(shapeError.message.orEmpty(), "content.md")
  }

  @Test
  fun `invalid skill md shape rejects frontmatter violations on content_md`() {
    val cases = listOf(
      Triple(
        "disallowed frontmatter key",
        { text: String -> text.replace("---\n", "---\nextra: nope\n", ignoreCase = false) },
        "extra",
      ),
      Triple(
        "missing name key",
        { text: String -> text.replace(Regex("(?m)^name:.*$"), "name:") },
        "name",
      ),
      Triple(
        "missing description key",
        { text: String -> text.replace(Regex("(?m)^description:.*$"), "description:") },
        "description",
      ),
    )
    cases.forEach { (label, mutate, discriminator) ->
      val root = copyFixture("valid_pack")
      val contentFile = root.resolve("code-review").resolve("content.md")
      Files.writeString(contentFile, mutate(Files.readString(contentFile)))

      val error = assertFailsWith<InvalidSkillMdShapeError>(label) {
        loadPlatformPack(root)
      }
      val message = error.message.orEmpty()
      assertTrue(message.isNotBlank(), label)
      assertContains(message, discriminator, message = label)
      assertContains(message, "content.md", message = label)
    }
  }

  @Test
  fun `valid frontmatter passes shape validator regardless of body markdown`() {
    // Body-shape rules (fenced code, H1, H3, tables, intro content) are no longer enforced
    // by the SkillMdShapeValidator on content.md callsites; only SKILL.md callers opt in via
    // validateBodyShape=true. This test confirms content.md tolerates rich body markdown all the
    // way through the loadPlatformPack integration path that consumes content.md in production.
    val root = copyFixture("valid_pack")
    val contentFile = root.resolve("code-review").resolve("content.md")
    val richBody = """
      |---
      |name: code-review
      |description: Fixture content with rich markdown to confirm the shape validator no longer rejects body markdown.
      |---
      |
      |# Top-level heading
      |
      |Some intro paragraph before any H2.
      |
      |### Subheading
      |
      || col1 | col2 |
      || ---- | ---- |
      || a    | b    |
      |
      |```kotlin
      |fun example(): Int = 42
      |```
      |
    """.trimMargin()
    Files.writeString(contentFile, richBody)
    // Calling the validator directly on the new content.md (frontmatter-only) must not throw.
    validateSkillMdShape(contentFile, validateBodyShape = false)
    // Loader integration must also accept rich body markdown end-to-end — guards against a
    // regression that would re-introduce body-shape enforcement on the content.md path.
    loadPlatformPack(root)
  }
}

private inline fun <reified T : Throwable> assertNamedFailure(fixtureName: String, expectedMessage: String) {
  val error = assertFailsWith<T> {
    loadPlatformPack(fixture(fixtureName))
  }
  assertContains(error.message.orEmpty(), fixtureName)
  assertContains(error.message.orEmpty(), expectedMessage)
}

private fun fixture(name: String): Path =
  repoRoot().resolve("tests").resolve("fixtures").resolve("shell_content_contract").resolve(name)

private fun copyFixture(name: String): Path {
  val source = fixture(name)
  val target = Files.createTempDirectory("skillbill-shell-content-fixture").resolve(name)
  Files.walk(source).use { stream ->
    stream.sorted().forEach { path ->
      val relative = source.relativize(path)
      val destination = target.resolve(relative)
      when {
        Files.isDirectory(path) -> Files.createDirectories(destination)
        Files.isSymbolicLink(path) -> Files.createSymbolicLink(destination, Files.readSymbolicLink(path))
        else -> {
          Files.createDirectories(destination.parent)
          Files.copy(path, destination)
        }
      }
    }
  }
  return target
}

private fun repoRoot(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasSkills = Files.isDirectory(current.resolve("skills"))
    if (hasSettings && hasSkills) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repository root from ${Path.of("").toAbsolutePath().normalize()}")
}
