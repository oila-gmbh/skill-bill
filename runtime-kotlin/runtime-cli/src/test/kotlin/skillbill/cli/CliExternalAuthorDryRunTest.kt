package skillbill.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliExternalAuthorDryRunTest {
  @Test
  fun `external author flow scaffolds validates links and removes a temporary platform pack`() {
    val fixture = createExternalAuthorDryRunFixture()
    try {
      val baselineSkill = scaffoldPlatformPack(fixture)
      assertValidationPasses(fixture.repoRoot, fixture.context)

      scaffoldQualityCheckOverride(fixture)
      assertValidationPasses(fixture.repoRoot, fixture.context)

      val installedLink = linkBaselineSkill(fixture, baselineSkill)

      Files.deleteIfExists(installedLink)
      deleteRecursively(fixture.packRoot)

      assertFalse(Files.exists(installedLink))
      assertFalse(Files.exists(fixture.packRoot))
      assertValidationPasses(fixture.repoRoot, fixture.context)
    } finally {
      deleteRecursively(fixture.packRoot)
      deleteRecursively(fixture.tempRoot)
    }
  }
}

private data class ExternalAuthorDryRunFixture(
  val tempRoot: Path,
  val userHome: Path,
  val repoRoot: Path,
  val platform: String,
  val packRoot: Path,
  val context: CliRuntimeContext,
)

private fun createExternalAuthorDryRunFixture(): ExternalAuthorDryRunFixture {
  val tempRoot = Files.createTempDirectory("skillbill-external-author")
  val userHome = tempRoot.resolve("home")
  val repoRoot = currentRepoRootForTest()
  val platform = "external-${System.nanoTime().toString(16)}"
  Files.createDirectories(userHome)

  return ExternalAuthorDryRunFixture(
    tempRoot = tempRoot,
    userHome = userHome,
    repoRoot = repoRoot,
    platform = platform,
    packRoot = repoRoot.resolve("platform-packs").resolve(platform),
    context = CliRuntimeContext(userHome = userHome),
  )
}

private fun scaffoldPlatformPack(fixture: ExternalAuthorDryRunFixture): Path {
  val payloadFile = fixture.tempRoot.resolve("external-pack-payload.json")
  Files.writeString(
    payloadFile,
    CliOutput.emit(externalPackPayload(fixture.repoRoot, fixture.platform), CliFormat.JSON),
  )

  val result =
    withTemporaryUserHome(fixture.userHome) {
      CliRuntime.run(listOf("new", "--payload", payloadFile.toString(), "--format", "json"), fixture.context)
    }
  val baselineSkill = fixture.packRoot.resolve("code-review").resolve("bill-${fixture.platform}-code-review")

  assertEquals(0, result.exitCode, result.stdout)
  assertEquals("ok", result.payload?.get("status"), result.stdout)
  assertTrue(Files.isDirectory(fixture.packRoot))
  assertTrue(Files.isRegularFile(baselineSkill.resolve("SKILL.md")))
  return baselineSkill
}

private fun scaffoldQualityCheckOverride(fixture: ExternalAuthorDryRunFixture) {
  preparePackForQualityCheckOverride(fixture.packRoot)
  val payloadFile = fixture.tempRoot.resolve("external-quality-check-override-payload.json")
  Files.writeString(
    payloadFile,
    CliOutput.emit(externalQualityCheckOverridePayload(fixture.repoRoot, fixture.platform), CliFormat.JSON),
  )

  val result =
    withTemporaryUserHome(fixture.userHome) {
      CliRuntime.run(listOf("new", "--payload", payloadFile.toString(), "--format", "json"), fixture.context)
    }
  val qualityCheckSkill = fixture.packRoot.resolve("quality-check").resolve("bill-${fixture.platform}-quality-check")

  assertEquals(0, result.exitCode, result.stdout)
  assertEquals("ok", result.payload?.get("status"), result.stdout)
  assertTrue(Files.isRegularFile(qualityCheckSkill.resolve("SKILL.md")))
}

private fun linkBaselineSkill(fixture: ExternalAuthorDryRunFixture, baselineSkill: Path): Path {
  val targetDir = fixture.tempRoot.resolve("agent").resolve("skills")
  val result =
    CliRuntime.run(
      listOf(
        "install",
        "link-skill",
        "--source",
        baselineSkill.toString(),
        "--target-dir",
        targetDir.toString(),
        "--agent",
        "codex",
      ),
      fixture.context,
    )
  val installedLink = targetDir.resolve("bill-${fixture.platform}-code-review")

  assertEquals(0, result.exitCode, result.stdout)
  assertTrue(Files.isSymbolicLink(installedLink))
  assertEquals(baselineSkill.toRealPath(), installedLink.toRealPath())
  return installedLink
}

private fun externalPackPayload(repoRoot: Path, platform: String): Map<String, Any?> = mapOf(
  "scaffold_payload_version" to "1.0",
  "kind" to "platform-pack",
  "platform" to platform,
  "skeleton_mode" to "starter",
  "display_name" to "External",
  "description" to "Use when reviewing external author fixture changes.",
  "routing_signals" to mapOf(
    "strong" to listOf("external.toml", "src/external"),
    "tie_breakers" to listOf("Prefer External when fixture markers dominate."),
  ),
  "repo_root" to repoRoot.toString(),
)

private fun externalQualityCheckOverridePayload(repoRoot: Path, platform: String): Map<String, Any?> = mapOf(
  "scaffold_payload_version" to "1.0",
  "kind" to "platform-override-piloted",
  "platform" to platform,
  "family" to "quality-check",
  "repo_root" to repoRoot.toString(),
)

private fun preparePackForQualityCheckOverride(packRoot: Path) {
  val manifestPath = packRoot.resolve("platform.yaml")
  val manifestWithoutQualityCheck =
    Files.readAllLines(manifestPath)
      .filterNot { line -> line.trimStart().startsWith("declared_quality_check_file:") }
      .joinToString("\n") + "\n"
  Files.writeString(manifestPath, manifestWithoutQualityCheck)
  deleteRecursively(packRoot.resolve("quality-check"))
}

private fun assertValidationPasses(repoRoot: Path, context: CliRuntimeContext) {
  val result =
    CliRuntime.run(
      listOf("validate", "--repo-root", repoRoot.toString(), "--format", "json"),
      context,
    )

  assertEquals(0, result.exitCode, result.stdout)
  assertEquals("pass", result.payload?.get("status"), result.stdout)
}

private fun <T> withTemporaryUserHome(userHome: Path, block: () -> T): T {
  val previous = System.getProperty("user.home")
  System.setProperty("user.home", userHome.toString())
  return try {
    block()
  } finally {
    if (previous == null) {
      System.clearProperty("user.home")
    } else {
      System.setProperty("user.home", previous)
    }
  }
}

private fun currentRepoRootForTest(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (true) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasSkills = Files.isDirectory(current.resolve("skills"))
    if (hasSettings && hasSkills) {
      return current
    }
    current = current.parent ?: return Path.of("").toAbsolutePath().normalize()
  }
}

private fun deleteRecursively(path: Path) {
  if (!Files.exists(path)) return
  Files.walk(path).use { stream ->
    stream
      .sorted(Comparator.reverseOrder())
      .forEach(Files::deleteIfExists)
  }
}
