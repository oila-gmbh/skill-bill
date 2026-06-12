package skillbill.install

import org.junit.jupiter.api.Assumptions
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.McpRegistrationApplyStatus
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

  /**
   * SKILL-76 AC-11: build a fixture whose `repoRoot` (and the derived skills/platform-packs roots)
   * is the COPY under `~/.skill-bill`, not the fetched clone. This is what subtask 1 repoints
   * `--repo-root` at. Returns a fixture sharing the original `home` so claude multi-root discovery,
   * the staging cache, and MCP config all resolve under the same home while the SOURCE location has
   * moved to the copy. Locks that the SKILL-74 fan-out + SKILL-75 MCP wiring are source-location
   * agnostic.
   */
  private fun copiedSourceFixture(seed: ApplyFixture): ApplyFixture {
    val copyRoot = seed.home.resolve(".skill-bill/source")
    Files.createDirectories(copyRoot)
    Files.walk(seed.repoRoot).use { stream ->
      stream.forEach { src ->
        val relative = seed.repoRoot.relativize(src)
        val dest = copyRoot.resolve(relative.toString())
        if (Files.isDirectory(src)) {
          Files.createDirectories(dest)
        } else {
          Files.createDirectories(dest.parent)
          Files.copy(src, dest)
        }
      }
    }
    return ApplyFixture(copyRoot, seed.home)
  }

  @Test
  fun `multi-root fan-out and per-profile MCP still resolve against the copied repoRoot`() {
    // SKILL-76 AC-11: with --repo-root pointing at the COPY under ~/.skill-bill, the SKILL-74
    // claude multi-profile fan-out still targets every resolved root's skills dir, every link
    // resolves into the shared staging cache (keyed off the copy, never the clone), and the
    // SKILL-75 per-profile MCP registration is unaffected (it keys off home, not the source).
    val seed = setupApplyFixture()
    val fixture = copiedSourceFixture(seed)
    assertTrue(
      fixture.repoRoot.startsWith(fixture.home.resolve(".skill-bill")),
      "guard: repoRoot must be the copy under ~/.skill-bill, was ${fixture.repoRoot}",
    )
    val defaultRoot = fixture.home.resolve(".claude").also { Files.createDirectories(it) }
    val workRoot = markClaudeProfile(fixture.home, ".claude-work")

    val plan = InstallOperations.planInstall(claudeMultiRootRequest(fixture))
    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(result.failures.isEmpty(), "unexpected failures: ${result.failures}")

    // (a) Fan-out: every selected skill links into BOTH resolved roots' skills dirs.
    val expectedSkillDirs = setOf(defaultRoot.resolve("skills"), workRoot.resolve("skills"))
      .map { it.toAbsolutePath().normalize() }
      .toSet()
    result.skills.forEach { skill ->
      val linkedParents = skill.links
        .filter { link -> link.status == InstallAgentLinkStatus.CREATED }
        .map { link -> link.linkPath.parent.toAbsolutePath().normalize() }
        .toSet()
      assertEquals(expectedSkillDirs, linkedParents, "skill ${skill.skillName} did not fan out to every root")
    }

    // Symlink targets resolve into the staging cache under home, NOT back into the copied source.
    val stagedTargets = result.skills.flatMap { skill ->
      skill.links
        .filter { link -> link.status == InstallAgentLinkStatus.CREATED }
        .map { link -> readSymlinkTarget(link.linkPath) }
    }
    stagedTargets.forEach { target ->
      assertTrue(
        target.startsWith(fixture.home.resolve(".skill-bill/installed-skills")),
        "link did not resolve into the staging cache: $target",
      )
      assertFalse(
        target.startsWith(fixture.repoRoot.toAbsolutePath().normalize()),
        "link must not resolve back into the copied source: $target",
      )
    }

    // (b) Per-profile MCP registration: unaffected by the source move; every claude outcome succeeds
    // and targets the home claude config (resolved off home, never off --repo-root/the copy).
    val claudeMcpOutcomes = result.mcpRegistrationOutcomes.filter { it.agent == InstallAgent.CLAUDE }
    assertTrue(claudeMcpOutcomes.isNotEmpty(), "claude MCP registration must be attempted")
    claudeMcpOutcomes.forEach { outcome ->
      assertEquals(McpRegistrationApplyStatus.SUCCESS, outcome.status, "claude MCP registration must succeed")
      assertEquals(
        fixture.home.resolve(".claude.json").toAbsolutePath().normalize(),
        outcome.configPath?.toAbsolutePath()?.normalize(),
        "MCP registration must target the home claude config, independent of --repo-root",
      )
    }
  }

  @Test
  fun `CLAUDE_CONFIG_DIR resolves the skill target independent of the copied repoRoot`() {
    // SKILL-76 AC-11: CLAUDE_CONFIG_DIR honoring is source-location agnostic. Moving --repo-root to
    // the copy must not change which root the skill target resolves to.
    val seed = setupApplyFixture()
    val fixture = copiedSourceFixture(seed)
    val workConfig = fixture.home.resolve(".claude-work")
    val env = mapOf(CLAUDE_CONFIG_DIR_ENV to workConfig.toString())

    assertEquals(
      workConfig.resolve("skills"),
      InstallOperations.agentPath("claude", fixture.home, environment = env),
      "CLAUDE_CONFIG_DIR must still resolve the skill target after --repo-root moved to the copy",
    )
  }

  @Test
  fun `apply links skills into every resolved claude root's skills dir using one shared staging cache`() {
    val fixture = setupApplyFixture()
    val defaultRoot = fixture.home.resolve(".claude").also { Files.createDirectories(it) }
    val workRoot = markClaudeProfile(fixture.home, ".claude-work")

    val plan = InstallOperations.planInstall(claudeMultiRootRequest(fixture))
    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(result.failures.isEmpty(), "unexpected failures: ${result.failures}")

    val expectedSkillDirs = setOf(defaultRoot.resolve("skills"), workRoot.resolve("skills"))
      .map { it.toAbsolutePath().normalize() }
      .toSet()
    result.skills.forEach { skill ->
      val linkedParents = skill.links
        .filter { link -> link.status == InstallAgentLinkStatus.CREATED }
        .map { link -> link.linkPath.parent.toAbsolutePath().normalize() }
        .toSet()
      assertEquals(expectedSkillDirs, linkedParents, "skill ${skill.skillName} did not fan out to every root")
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

    val defaultSkills = defaultRoot.resolve("skills")
    val firstDefaultLinks = symlinkTargetsIn(defaultSkills)
    assertTrue(firstDefaultLinks.isNotEmpty(), "default root should receive links on the first apply")

    val workRoot = markClaudeProfile(fixture.home, ".claude-work")
    val workSkills = workRoot.resolve("skills")

    val secondResult = InstallOperations.applyInstall(InstallOperations.planInstall(claudeMultiRootRequest(fixture)))
    assertEquals(InstallApplyStatus.SUCCESS, secondResult.status)
    assertTrue(secondResult.failures.isEmpty(), "unexpected failures: ${secondResult.failures}")

    assertEquals(
      firstDefaultLinks,
      symlinkTargetsIn(defaultSkills),
      "pre-existing root links should be idempotent across re-apply",
    )
    assertTrue(
      symlinkTargetsIn(workSkills).isNotEmpty(),
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
