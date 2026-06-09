package skillbill.install

import org.junit.jupiter.api.Assumptions
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallApplyClaudeMultiRootTest : InstallApplyTestSupport() {
  private fun markClaudeProfile(home: Path, name: String): Path {
    val root = home.resolve(name)
    Files.createDirectories(root)
    Files.createFile(root.resolve(".claude.json"))
    return root
  }

  private fun claudeMultiRootRequest(fixture: ApplyFixture) = fixture.request(agents = setOf(InstallAgent.CLAUDE))
    .let { base ->
      base.copy(
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = setOf(InstallAgent.CLAUDE),
        ),
        targetPaths = base.targetPaths.copy(agentTargets = emptyList()),
      )
    }

  @Test
  fun `apply links skills into every resolved claude root's commands using one shared staging cache`() {
    val fixture = setupApplyFixture()
    val defaultRoot = fixture.home.resolve(".claude").also { Files.createDirectories(it) }
    val workRoot = markClaudeProfile(fixture.home, ".claude-work")

    val plan = InstallOperations.planInstall(claudeMultiRootRequest(fixture))
    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(result.failures.isEmpty(), "unexpected failures: ${result.failures}")

    val expectedCommandDirs = setOf(defaultRoot.resolve("commands"), workRoot.resolve("commands"))
      .map { it.toAbsolutePath().normalize() }
      .toSet()
    result.skills.forEach { skill ->
      val linkedParents = skill.links
        .filter { link -> link.status == InstallAgentLinkStatus.CREATED }
        .map { link -> link.linkPath.parent.toAbsolutePath().normalize() }
        .toSet()
      assertEquals(expectedCommandDirs, linkedParents, "skill ${skill.skillName} did not fan out to every root")
    }

    // Shared staging cache: links across roots point at the same staged target, not per-root copies.
    val stagedTargets = result.skills.flatMap { skill ->
      skill.links
        .filter { link -> link.status == InstallAgentLinkStatus.CREATED }
        .map { link -> readSymlinkTarget(link.linkPath) }
    }
    stagedTargets.forEach { target ->
      assertTrue(
        target.startsWith(fixture.home.resolve(".skill-bill/installed-skills")),
        "link did not resolve into the shared staging cache: $target",
      )
    }
  }

  @Test
  fun `re-running apply is idempotent for existing roots and links newly created profiles`() {
    val fixture = setupApplyFixture()
    val defaultRoot = fixture.home.resolve(".claude").also { Files.createDirectories(it) }

    val firstResult = InstallOperations.applyInstall(InstallOperations.planInstall(claudeMultiRootRequest(fixture)))
    assertEquals(InstallApplyStatus.SUCCESS, firstResult.status)

    val defaultCommands = defaultRoot.resolve("commands")
    val firstDefaultLinks = symlinkTargetsIn(defaultCommands)
    assertTrue(firstDefaultLinks.isNotEmpty(), "default root should receive links on the first apply")

    val workRoot = markClaudeProfile(fixture.home, ".claude-work")
    val workCommands = workRoot.resolve("commands")

    val secondResult = InstallOperations.applyInstall(InstallOperations.planInstall(claudeMultiRootRequest(fixture)))
    assertEquals(InstallApplyStatus.SUCCESS, secondResult.status)
    assertTrue(secondResult.failures.isEmpty(), "unexpected failures: ${secondResult.failures}")

    assertEquals(
      firstDefaultLinks,
      symlinkTargetsIn(defaultCommands),
      "pre-existing root links should be idempotent across re-apply",
    )
    assertTrue(
      symlinkTargetsIn(workCommands).isNotEmpty(),
      "newly created profile commands dir should get links on re-apply",
    )
  }

  private fun symlinkTargetsIn(dir: Path): Map<Path, Path> {
    if (!Files.isDirectory(dir)) return emptyMap()
    val symlinks = Files.list(dir).use { stream ->
      stream.filter { entry -> Files.isSymbolicLink(entry) }.toList()
    }
    return symlinks.associate { link -> link.fileName to readSymlinkTarget(link) }
  }

  @Test
  fun `native claude subagents link into every resolved root's agents directory`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".claude"))
    val workRoot = markClaudeProfile(fixture.home, ".claude-work")

    val plan = InstallOperations.planInstall(
      claudeMultiRootRequest(fixture).copy(
        platformPackSelection = skillbill.install.model.PlatformPackSelection(
          mode = skillbill.install.model.PlatformPackSelectionMode.SELECTED,
          selectedSlugs = setOf("kotlin"),
        ),
      ),
    )
    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val linkedClaudeAgentDirs = result.nativeAgents
      .filter { native -> native.provider == NativeAgentProviderId.CLAUDE }
      .filter { native -> native.status == NativeAgentApplyStatus.LINKED }
      .mapNotNull { native -> native.path?.parent?.toAbsolutePath()?.normalize() }
      .toSet()

    assertTrue(linkedClaudeAgentDirs.contains(fixture.home.resolve(".claude/agents").toAbsolutePath().normalize()))
    assertTrue(linkedClaudeAgentDirs.contains(workRoot.resolve("agents").toAbsolutePath().normalize()))
  }

  @Test
  fun `native claude subagent unlink fans out across every resolved root's agents directory`() {
    val fixture = setupApplyFixture()
    val defaultAgentsDir = fixture.home.resolve(".claude/agents")
    val workRoot = markClaudeProfile(fixture.home, ".claude-work")
    Files.createDirectories(fixture.home.resolve(".claude"))

    val request = NativeAgentLinkRequest(
      platformPacksRoot = fixture.repoRoot.resolve("platform-packs"),
      skillsRoot = fixture.repoRoot.resolve("skills"),
      home = fixture.home,
      selectedPlatforms = listOf("kotlin"),
    )

    val linked = InstallNativeAgentOperations.linkClaudeAgents(request).linked
    val linkedDirs = linked.map { it.parent.toAbsolutePath().normalize() }.toSet()
    Assumptions.assumeTrue(linked.isNotEmpty(), "symlinks unsupported on this filesystem")
    assertTrue(
      linkedDirs.contains(defaultAgentsDir.toAbsolutePath().normalize()),
      "default root not linked: $linkedDirs",
    )
    assertTrue(
      linkedDirs.contains(workRoot.resolve("agents").toAbsolutePath().normalize()),
      "work root not linked: $linkedDirs",
    )

    val removed = InstallNativeAgentOperations.unlinkClaudeAgents(request)
    val removedDirs = removed.map { it.parent.toAbsolutePath().normalize() }.toSet()

    assertTrue(
      removedDirs.contains(defaultAgentsDir.toAbsolutePath().normalize()),
      "default root not unlinked: $removedDirs",
    )
    assertTrue(
      removedDirs.contains(workRoot.resolve("agents").toAbsolutePath().normalize()),
      "work root not unlinked: $removedDirs",
    )
    linked.forEach { link ->
      assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "link survived unlink: $link")
    }
  }
}
