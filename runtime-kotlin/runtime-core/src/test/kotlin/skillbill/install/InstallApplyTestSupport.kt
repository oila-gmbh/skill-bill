package skillbill.install

import org.junit.jupiter.api.Assumptions
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallAgentTargetSource
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

abstract class InstallApplyTestSupport {
  protected val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  protected fun setupApplyFixture(): ApplyFixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-apply-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-apply-home").also(tempDirs::add)
    seedBaseSkill(repoRoot, "bill-code-review", nativeAgentName = "bill-code-review-worker")
    seedBaseSkill(repoRoot, "bill-quality-check")
    seedPlatformPack(repoRoot, "kotlin", nativeAgentName = "bill-kotlin-code-review-worker")
    seedPlatformPack(repoRoot, "kmp", nativeAgentName = "bill-kmp-code-review-worker")
    return ApplyFixture(repoRoot, home)
  }

  protected fun snapshotSource(root: Path): Map<String, String> = Files.walk(root).use { stream ->
    stream
      .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
      .sorted()
      .toList()
      .associate { path ->
        root.relativize(path).toString().replace(java.io.File.separatorChar, '/') to Files.readString(path)
      }
  }

  protected fun assertSourceUnchanged(root: Path, before: Map<String, String>) {
    assertEquals(before, snapshotSource(root), "install flow mutated source files")
  }

  protected fun assertStagingUnderHomeCacheAndOutsideSource(fixture: ApplyFixture, stagingDir: Path?, label: String) {
    val staged = assertNotNull(stagingDir, "$label did not expose a staging directory")
    assertTrue(staged.startsWith(fixture.home.resolve(".skill-bill/installed-skills")), "$label staged outside cache")
    assertFalse(staged.startsWith(fixture.repoRoot), "$label staged inside source")
  }

  protected fun assertNativeProviders(actual: Set<NativeAgentProviderId>, expected: Set<NativeAgentProviderId>) {
    assertEquals(expected, actual.filter { provider -> provider in expected }.toSet())
  }

  protected fun createSymlinkOrSkip(linkPath: Path, target: Path) {
    val created = runCatching {
      Files.createSymbolicLink(linkPath, target)
    }.isSuccess
    Assumptions.assumeTrue(created, "symlinks unsupported on this filesystem")
  }

  protected fun readPosixPermissionsOrSkip(path: Path): Set<PosixFilePermission> {
    val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
    Assumptions.assumeTrue(permissions != null, "POSIX permissions unsupported on this filesystem")
    return permissions.orEmpty()
  }

  protected fun readSymlinkTarget(linkPath: Path): Path {
    val target = Files.readSymbolicLink(linkPath)
    val resolved = if (target.isAbsolute) target else linkPath.parent.resolve(target)
    return resolved.toAbsolutePath().normalize()
  }

  protected fun seedBaseSkill(repoRoot: Path, name: String, nativeAgentName: String? = null) {
    val skillDir = repoRoot.resolve("skills/$name")
    Files.createDirectories(skillDir)
    Files.writeString(skillDir.resolve("content.md"), content(name))
    nativeAgentName?.let { seedNativeAgent(skillDir, it) }
  }

  protected fun seedPlatformPack(repoRoot: Path, slug: String, nativeAgentName: String? = null) {
    val packRoot = repoRoot.resolve("platform-packs/$slug")
    val codeReviewName = "bill-$slug-code-review"
    val qualityCheckName = "bill-$slug-quality-check"
    val codeReviewDir = packRoot.resolve("code-review/$codeReviewName")
    val qualityCheckDir = packRoot.resolve("quality-check/$qualityCheckName")
    Files.createDirectories(codeReviewDir)
    Files.createDirectories(qualityCheckDir)
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      |platform: "$slug"
      |contract_version: "1.1"
      |routing_signals:
      |  strong:
      |    - "$slug"
      |  tie_breakers: []
      |declared_code_review_areas: []
      |declared_files:
      |  baseline: "code-review/$codeReviewName/content.md"
      |  areas: {}
      |area_metadata: {}
      |display_name: "$slug"
      |declared_quality_check_file: "quality-check/$qualityCheckName/content.md"
      |
      """.trimMargin(),
    )
    Files.writeString(codeReviewDir.resolve("content.md"), content(codeReviewName))
    Files.writeString(qualityCheckDir.resolve("content.md"), content(qualityCheckName))
    nativeAgentName?.let { seedNativeAgent(codeReviewDir, it) }
  }

  private fun seedNativeAgent(skillDir: Path, name: String) {
    val nativeAgentDir = skillDir.resolve("native-agents")
    Files.createDirectories(nativeAgentDir)
    Files.writeString(
      nativeAgentDir.resolve("$name.md"),
      """
      |---
      |name: $name
      |description: Test native agent.
      |---
      |
      |# $name
      |
      |Do the work.
      |
      """.trimMargin(),
    )
  }

  protected fun content(name: String): String = """
    |---
    |name: $name
    |description: Test skill.
    |---
    |
    |Test body.
    |
  """.trimMargin()

  protected companion object {
    val allInstallAgents: Set<InstallAgent> = InstallAgent.entries.toSet()
  }
}

data class ApplyFixture(
  val repoRoot: Path,
  val home: Path,
) {
  fun request(
    selectedPlatforms: Set<String> = emptySet(),
    agents: Set<InstallAgent> = setOf(InstallAgent.CODEX, InstallAgent.CLAUDE),
    telemetryLevel: InstallTelemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice: McpRegistrationChoice = McpRegistrationChoice(
      register = true,
      runtimeMcpBin = home.resolve(".skill-bill/runtime/runtime-mcp/bin/runtime-mcp"),
    ),
    windowsSymlinkPreflight: WindowsSymlinkPreflight = WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.NOT_WINDOWS,
      decision = WindowsSymlinkDecision.NOT_REQUIRED,
    ),
  ): InstallPlanRequest {
    val platformSelectionMode = if (selectedPlatforms.isEmpty()) {
      PlatformPackSelectionMode.NONE
    } else {
      PlatformPackSelectionMode.SELECTED
    }
    val targetPaths = InstallationTargetPaths(
      skillsRoot = repoRoot.resolve("skills"),
      platformPacksRoot = repoRoot.resolve("platform-packs"),
      agentTargets = agents.map { agent ->
        InstallAgentTarget(
          agent = agent,
          path = home.resolve("agent-skill-targets/${agent.id}"),
          source = InstallAgentTargetSource.MANUAL,
        )
      },
    )
    return InstallPlanRequest(
      repoRoot = repoRoot,
      home = home,
      agentSelection = InstallAgentSelection(
        mode = InstallAgentSelectionMode.MANUAL,
        manualAgents = agents,
      ),
      platformPackSelection = PlatformPackSelection(
        mode = platformSelectionMode,
        selectedSlugs = selectedPlatforms,
      ),
      telemetryLevel = telemetryLevel,
      mcpRegistrationChoice = mcpRegistrationChoice,
      runtimeDistributionInputs = RuntimeDistributionInputs(
        runtimeInstallRoot = home.resolve(".skill-bill/runtime"),
      ),
      targetPaths = targetPaths,
      windowsSymlinkPreflight = windowsSymlinkPreflight,
    )
  }
}
