package skillbill.cli

import skillbill.cli.core.CliOutput
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRuntimeContext
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertContains
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

  @Test
  fun `external add-on scaffold registers source in config`() {
    val fixture = createExternalAuthorDryRunFixture()
    val configPath = fixture.tempRoot.resolve("config.json")
    val context = fixture.context.copy(environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()))
    val externalDir = fixture.tempRoot.resolve("external-addons")
    try {
      scaffoldPlatformPack(fixture)
      val result =
        CliRuntime.run(
          listOf("new", "--payload", "-", "--format", "json"),
          context.copy(
            stdinText = CliOutput.emit(
              externalAddonPayload(fixture.repoRoot, fixture.platform, externalDir),
              CliFormat.JSON,
            ),
          ),
        )
      val resolved =
        CliRuntime.run(listOf("config", "resolve-external-addons"), context)

      assertEquals(0, result.exitCode, result.stdout)
      assertEquals("ok", result.payload?.get("status"), result.stdout)
      assertTrue(Files.isRegularFile(externalDir.resolve("awesome-review.md")))
      assertTrue(Files.isRegularFile(externalDir.resolve("addon-manifest.yaml")))
      assertContains(resolved.stdout, "${fixture.platform}\t${externalDir.toAbsolutePath().normalize()}")
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
  val repoRoot = createIsolatedRepoFixture(currentRepoRootForTest(), tempRoot.resolve("repo"))
  val platform = "external-${System.nanoTime().toString(16)}"
  Files.createDirectories(userHome)

  return ExternalAuthorDryRunFixture(
    tempRoot = tempRoot,
    userHome = userHome,
    repoRoot = repoRoot,
    platform = platform,
    packRoot = repoRoot.resolve("platform-packs").resolve(platform),
    context = CliRuntimeContext(userHome = userHome, environment = emptyMap()),
  )
}

private fun createIsolatedRepoFixture(sourceRepoRoot: Path, targetRepoRoot: Path): Path {
  val directories = listOf("skills", "platform-packs", "orchestration", ".agents", ".claude-plugin")
  directories.forEach { relativePath ->
    copyTree(sourceRepoRoot.resolve(relativePath), targetRepoRoot.resolve(relativePath))
  }
  Files.copy(
    sourceRepoRoot.resolve("README.md"),
    targetRepoRoot.resolve("README.md"),
    StandardCopyOption.COPY_ATTRIBUTES,
  )
  return targetRepoRoot
}

private fun copyTree(source: Path, target: Path) {
  Files.walkFileTree(
    source,
    object : SimpleFileVisitor<Path>() {
      override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
        val targetDir = target.resolve(source.relativize(dir))
        Files.createDirectories(targetDir)
        return FileVisitResult.CONTINUE
      }

      override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        val targetFile = target.resolve(source.relativize(file))
        Files.copy(file, targetFile, StandardCopyOption.COPY_ATTRIBUTES)
        return FileVisitResult.CONTINUE
      }
    },
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
  assertTrue(Files.isRegularFile(baselineSkill.resolve("content.md")))
  return baselineSkill
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
  "display_name" to "External",
  "description" to "Use when reviewing external author fixture changes.",
  "routing_signals" to mapOf(
    "strong" to listOf("external.toml", "src/external"),
    "tie_breakers" to listOf("Prefer External when fixture markers dominate."),
  ),
  "repo_root" to repoRoot.toString(),
)

private fun externalAddonPayload(repoRoot: Path, platform: String, externalDir: Path): Map<String, Any?> = mapOf(
  "scaffold_payload_version" to "1.0",
  "kind" to "add-on",
  "platform" to platform,
  "name" to "awesome-review",
  "addon_location_path" to externalDir.toString(),
  "repo_root" to repoRoot.toString(),
)

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
    val hasPlatformPacks = Files.isDirectory(current.resolve("platform-packs"))
    if (hasSettings && hasSkills && hasPlatformPacks) {
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
