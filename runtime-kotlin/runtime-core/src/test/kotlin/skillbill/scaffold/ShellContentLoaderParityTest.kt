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

    val shapeRoot = copyFixture("valid_pack")
    val shapeSkill = shapeRoot.resolve("code-review").resolve("SKILL.md")
    Files.writeString(shapeSkill, Files.readString(shapeSkill) + "\n### Not allowed here\n")
    val shapeError = assertFailsWith<InvalidSkillMdShapeError> {
      loadPlatformPack(shapeRoot)
    }
    assertContains(shapeError.message.orEmpty(), "H3+ heading")
  }

  @Test
  fun `invalid skill md shape rejects high value governed wrapper drift categories`() {
    listOf(
      "disallowed frontmatter" to { text: String -> text.replace("---\n", "---\nextra: nope\n") },
      "intro content" to { text: String -> text.replace("## Descriptor", "Intro paragraph.\n\n## Descriptor") },
      "fenced code block" to { text: String -> text.replace("## Ceremony", "```\ncode\n```\n\n## Ceremony") },
      "markdown table" to { text: String -> text.replace("## Ceremony", "| a | b |\n| - | - |\n\n## Ceremony") },
      "H1 heading" to { text: String -> text.replace("## Ceremony", "# Heading\n\n## Ceremony") },
      "telemetry instruction" to { text: String ->
        text.replace("## Ceremony", "skillbill_review_finished\n\n## Ceremony")
      },
      "wrong H2 order" to ::moveExecutionBeforeDescriptor,
    ).forEach { (label, mutate) ->
      val root = copyFixture("valid_pack")
      val skill = root.resolve("code-review").resolve("SKILL.md")
      Files.writeString(skill, mutate(Files.readString(skill)))

      val error = assertFailsWith<InvalidSkillMdShapeError>(label) {
        loadPlatformPack(root)
      }
      assertTrue(error.message.orEmpty().contains("SKILL.md"), error.message.orEmpty())
    }
  }
}

private fun moveExecutionBeforeDescriptor(text: String): String {
  val descriptorStart = text.indexOf("## Descriptor")
  val executionStart = text.indexOf("## Execution")
  val ceremonyStart = text.indexOf("## Ceremony")
  check(descriptorStart >= 0 && executionStart > descriptorStart && ceremonyStart > executionStart)
  return buildString {
    append(text.substring(0, descriptorStart))
    append(text.substring(executionStart, ceremonyStart))
    append("\n")
    append(text.substring(descriptorStart, executionStart))
    append(text.substring(ceremonyStart))
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
