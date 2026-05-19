package skillbill.scaffold

import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidSkillMdShapeError
import skillbill.error.MissingContentFileError
import skillbill.error.MissingManifestError
import skillbill.error.MissingRequiredSectionError
import skillbill.testing.repoRootFromTest
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
    assertEquals("code-review", pack.declaredFiles.baseline?.parent?.name)
  }

  @Test
  fun `loads optional quality check content without fallback`() {
    val pack = loadPlatformPack(fixture("code_review_and_quality_check"))
    val contentPath = loadQualityCheckContent(pack)

    assertEquals(pack.declaredQualityCheckFile, contentPath)
    assertTrue(Files.isRegularFile(contentPath))
  }

  @Test
  fun `declared governed skill paths must point directly at content_md`() {
    val cases: List<Pair<String, (Path) -> Unit>> = listOf(
      "declared_files.baseline" to { manifest ->
        Files.writeString(
          manifest,
          Files.readString(manifest).replace(
            "baseline: code-review/content.md",
            "baseline: code-review/SKILL.md",
          ),
        )
      },
      "declared_files.areas.architecture" to { manifest ->
        Files.writeString(
          manifest,
          Files.readString(manifest).replace(
            "architecture: code-review/architecture/content.md",
            "architecture: code-review/architecture.md",
          ),
        )
      },
      "declared_quality_check_file" to { manifest ->
        Files.writeString(
          manifest,
          Files.readString(manifest).replace(
            "declared_quality_check_file: quality-check/content.md",
            "declared_quality_check_file: quality-check/SKILL.md",
          ),
        )
      },
    )

    cases.forEach { (field, mutateManifest) ->
      val fixtureName = if (field == "declared_quality_check_file") {
        "code_review_and_quality_check"
      } else {
        "valid_pack"
      }
      val root = copyFixture(fixtureName)
      val manifest = root.resolve("platform.yaml")
      mutateManifest(manifest)

      val error = assertFailsWith<InvalidManifestSchemaError>(field) {
        loadPlatformPack(root)
      }
      assertContains(error.message.orEmpty(), field)
      assertContains(error.message.orEmpty(), "content.md")
    }
  }

  @Test
  fun `loud fails with named shell content contract errors`() {
    assertNamedFailure<MissingManifestError>("missing_manifest", "platform.yaml")
    assertNamedFailure<MissingContentFileError>("missing_content_file", "baseline")
    assertNamedFailure<ContractVersionMismatchError>("bad_version", "9.99")
    assertNamedFailure<InvalidManifestSchemaError>("invalid_schema", "routing_signals")
    assertNamedFailure<InvalidManifestSchemaError>("schema_areas_wrong_type", "declared_code_review_areas")
    assertNamedFailure<InvalidManifestSchemaError>("schema_unapproved_area", "laravel")
    assertNamedFailure<InvalidManifestSchemaError>("extra_area", "performance")
  }

  @Test
  fun `quality check declaration fails loudly when declared content file is missing`() {
    val missingFilePack = loadPlatformManifest(fixture("quality_check_missing_file"))
    val missingFileError = assertFailsWith<MissingContentFileError> {
      loadQualityCheckContent(missingFilePack)
    }
    assertContains(missingFileError.message.orEmpty(), "does-not-exist/content.md")
  }

  @Test
  fun `loader validates content_md frontmatter instead of generated wrapper body shape`() {
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
    assertContains(shapeError.message.orEmpty(), "content.md")
  }

  @Test
  fun `loader requires non empty authored content`() {
    val root = copyFixture("valid_pack")
    val contentFile = root.resolve("code-review").resolve("content.md")
    Files.writeString(
      contentFile,
      "---\nname: code-review\ndescription: Empty authored content fixture.\n---\n",
    )

    val error = assertFailsWith<MissingRequiredSectionError> {
      loadPlatformPack(root)
    }
    assertContains(error.message.orEmpty(), "authored content")
    assertContains(error.message.orEmpty(), "content.md")
  }

  @Test
  fun `loader rejects title only authored content`() {
    val root = copyFixture("valid_pack")
    val contentFile = root.resolve("code-review").resolve("content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: code-review
      description: Title-only authored content fixture.
      ---

      # Fixture Review Content
      """.trimIndent() + "\n",
    )

    val error = assertFailsWith<MissingRequiredSectionError> {
      loadPlatformPack(root)
    }
    assertContains(error.message.orEmpty(), "authored guidance beyond the title heading")
    assertContains(error.message.orEmpty(), "content.md")
  }

  @Test
  fun `loader rejects generated wrapper boilerplate and self referential content pointer`() {
    val wrapperRoot = copyFixture("valid_pack")
    val wrapperContent = wrapperRoot.resolve("code-review").resolve("content.md")
    Files.writeString(
      wrapperContent,
      """
      ---
      name: code-review
      description: Wrapper boilerplate fixture.
      ---

      # Fixture Review Content

      Review the fixture implementation.

      ## Ceremony

      Generated wrapper ceremony does not belong here.
      """.trimIndent() + "\n",
    )

    val wrapperError = assertFailsWith<MissingRequiredSectionError> {
      loadPlatformPack(wrapperRoot)
    }
    assertContains(wrapperError.message.orEmpty(), "generated wrapper boilerplate heading '## Ceremony'")

    val pointerRoot = copyFixture("valid_pack")
    val pointerContent = pointerRoot.resolve("code-review").resolve("architecture").resolve("content.md")
    Files.writeString(
      pointerContent,
      """
      ---
      name: code-review
      description: Self-referential pointer fixture.
      ---

      Follow the instructions in [content.md](content.md).
      """.trimIndent() + "\n",
    )

    val pointerError = assertFailsWith<MissingRequiredSectionError> {
      loadPlatformPack(pointerRoot)
    }
    assertContains(pointerError.message.orEmpty(), "self-referential wrapper pointer text")
  }

  @Test
  fun `area and quality check declarations require non empty authored content`() {
    val areaRoot = copyFixture("valid_pack")
    val areaContent = areaRoot.resolve("code-review").resolve("architecture").resolve("content.md")
    Files.writeString(
      areaContent,
      "---\nname: code-review\ndescription: Empty architecture area fixture.\n---\n",
    )

    val areaError = assertFailsWith<MissingRequiredSectionError> {
      loadPlatformPack(areaRoot)
    }
    assertContains(areaError.message.orEmpty(), "authored content")
    assertContains(areaError.message.orEmpty(), "code-review/architecture/content.md")

    val qualityRoot = copyFixture("code_review_and_quality_check")
    val qualityContent = qualityRoot.resolve("quality-check").resolve("content.md")
    Files.writeString(
      qualityContent,
      "---\nname: quality-check\ndescription: Empty quality-check fixture.\n---\n",
    )
    val pack = loadPlatformManifest(qualityRoot)

    val qualityError = assertFailsWith<MissingRequiredSectionError> {
      loadQualityCheckContent(pack)
    }
    assertContains(qualityError.message.orEmpty(), "authored content")
    assertContains(qualityError.message.orEmpty(), "quality-check/content.md")
  }

  @Test
  fun `quality check declaration validates content_md frontmatter`() {
    val root = copyFixture("code_review_and_quality_check")
    val contentFile = root.resolve("quality-check").resolve("content.md")
    Files.writeString(
      contentFile,
      Files.readString(contentFile).replace(
        Regex("(?m)^description:.*$"),
        "description:",
      ),
    )
    val pack = loadPlatformManifest(root)

    val error = assertFailsWith<InvalidSkillMdShapeError> {
      loadQualityCheckContent(pack)
    }
    assertContains(error.message.orEmpty(), "description")
    assertContains(error.message.orEmpty(), "quality-check/content.md")
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
  repoRootFromTest().resolve("tests").resolve("fixtures").resolve("shell_content_contract").resolve(name)

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
