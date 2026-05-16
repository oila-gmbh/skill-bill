package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkFallbackState
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallApplyTest : InstallApplyTestSupport() {
  @Test
  fun `apply returns structured success for selected skills platforms agents and native agents`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.createDirectories(fixture.home.resolve(".claude"))
    val sourceBefore = snapshotSource(fixture.repoRoot)
    val plan = InstallOperations.planInstall(fixture.request(selectedPlatforms = setOf("kotlin")))

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(result.failures.isEmpty(), "unexpected failures: ${result.failures}")
    assertEquals(InstallTelemetryLevel.ANONYMOUS, result.telemetryLevel)
    assertTrue(result.mcpRegistrationIntent.register)
    val skillsByName = result.skills.associateBy { skill -> skill.skillName }
    assertEquals(
      setOf(
        "bill-code-review",
        "bill-quality-check",
        "bill-kotlin-code-review",
        "bill-kotlin-quality-check",
      ),
      skillsByName.keys,
    )
    assertEquals(InstallPlanSkillKind.BASE, skillsByName.getValue("bill-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-kotlin-code-review").kind)
    assertFalse(skillsByName.containsKey("bill-kmp-code-review"), "unselected platform skill was applied")
    result.skills.forEach { skill ->
      assertEquals(InstallSkillStagingStatus.STAGED, skill.staging.status)
      val stagingDir = assertNotNull(skill.staging.stagingDir)
      assertTrue(stagingDir.startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
      assertFalse(stagingDir.startsWith(fixture.repoRoot), "staging escaped into source for ${skill.skillName}")
      assertEquals(setOf(InstallAgent.CODEX, InstallAgent.CLAUDE), skill.links.map { link -> link.agent }.toSet())
      assertTrue(skill.links.all { link -> link.status == InstallAgentLinkStatus.CREATED })
    }
    assertEquals(sourceBefore, snapshotSource(fixture.repoRoot), "apply mutated source files")
    assertNativeProviders(
      result.nativeAgents.map { native -> native.provider }.toSet(),
      setOf(NativeAgentProviderId.CLAUDE, NativeAgentProviderId.CODEX),
    )
    val nativeArtifactNames = result.nativeAgents.mapNotNull { native -> native.path?.fileName?.toString() }
    assertTrue(nativeArtifactNames.any { name -> "bill-kotlin-code-review-worker" in name })
    assertFalse(nativeArtifactNames.any { name -> "bill-kmp-code-review-worker" in name })
    assertTrue(result.nativeAgents.any { native -> native.status == NativeAgentApplyStatus.LINKED })
    result.nativeAgents
      .filter { native -> native.status == NativeAgentApplyStatus.LINKED }
      .mapNotNull { native -> native.path }
      .forEach { link ->
        val target = readSymlinkTarget(link)
        assertTrue(target.startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
        assertFalse(target.startsWith(fixture.home.resolve(".skill-bill/native-agents")))
      }
    assertFalse(Files.exists(fixture.home.resolve(".skill-bill/native-agents"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(result.nativeAgents.any { native -> native.provider == NativeAgentProviderId.OPENCODE })
    assertFalse(result.nativeAgents.any { native -> native.provider == NativeAgentProviderId.JUNIE })
  }

  @Test
  fun `reapply reports existing skill and native links as structured skipped outcomes`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )
    InstallOperations.applyInstall(plan)

    val second = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, second.status)
    assertTrue(
      second.skills.flatMap { skill -> skill.links }.all { link ->
        link.status == InstallAgentLinkStatus.SKIPPED && link.message.contains("already linked")
      },
    )
    assertTrue(
      second.nativeAgents.any { native ->
        native.provider == NativeAgentProviderId.CODEX &&
          native.status == NativeAgentApplyStatus.SKIPPED &&
          native.message.contains("already linked")
      },
    )
  }

  @Test
  fun `apply uses Windows preflight state as structured guidance without attempting writes`() {
    val fixture = setupApplyFixture()
    val sourceBefore = snapshotSource(fixture.repoRoot)
    val plan = InstallOperations.planInstall(
      fixture.request(
        windowsSymlinkPreflight = WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.DECISION_REQUIRED,
          decision = WindowsSymlinkDecision.REQUIRE_USER_ACTION,
          message = "Windows requires elevation or Developer Mode before symlink install.",
        ),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertEquals(WindowsSymlinkFallbackState.USER_ACTION_REQUIRED, result.windowsSymlinkOutcome.fallbackState)
    assertContains(result.windowsSymlinkOutcome.guidance, "Developer Mode")
    assertContains(result.windowsSymlinkOutcome.guidance, "elevated shell")
    assertTrue(result.skills.isEmpty(), "preflight failure should stop before staging/linking")
    assertEquals(sourceBefore, snapshotSource(fixture.repoRoot))
    assertFalse(Files.exists(fixture.home.resolve(".skill-bill/installed-skills"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(fixture.home.resolve("agent-skill-targets"), LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `apply maps native agent source validation failures into structured staging failures`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.writeString(
      fixture.repoRoot
        .resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/native-agents/bill-malformed.md"),
      """
      |---
      |name: bill-malformed
      |---
      |
      |# Missing description
      |
      """.trimMargin(),
    )
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(result.nativeAgents.isEmpty(), "staging failure should stop native-agent apply")
    assertTrue(
      result.failures.any { failure ->
        failure.kind == InstallApplyIssueKind.STAGING_FAILED &&
          failure.skillName == "bill-kotlin-code-review" &&
          failure.message.contains("native agent description is required")
      },
    )
  }

  @Test
  fun `apply ignores malformed native agents from unselected platform packs`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    Files.writeString(
      fixture.repoRoot
        .resolve("platform-packs/kmp/code-review/bill-kmp-code-review/native-agents/bill-malformed.md"),
      """
      |---
      |name: bill-malformed
      |---
      |
      |# Missing description in unselected pack
      |
      """.trimMargin(),
    )
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val nativeArtifactNames = result.nativeAgents.mapNotNull { native -> native.path?.fileName?.toString() }
    assertTrue(nativeArtifactNames.any { name -> "bill-kotlin-code-review-worker" in name })
    assertFalse(nativeArtifactNames.any { name -> "bill-kmp-code-review-worker" in name })
    assertFalse(nativeArtifactNames.any { name -> "bill-malformed" in name })
  }

  @Test
  fun `apply ignores native agents outside selected planned skill roots`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val legacySkillNativeAgents = fixture.repoRoot.resolve("skills/kmp/bill-kmp-legacy/native-agents")
    Files.createDirectories(legacySkillNativeAgents)
    Files.writeString(
      legacySkillNativeAgents.resolve("bill-kmp-legacy-worker.md"),
      """
      |---
      |name: bill-kmp-legacy-worker
      |---
      |
      |# Missing description in unselected legacy skill root
      |
      """.trimMargin(),
    )
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val nativeArtifactNames = result.nativeAgents.mapNotNull { native -> native.path?.fileName?.toString() }
    assertFalse(nativeArtifactNames.any { name -> "bill-kmp-legacy-worker" in name })
  }

  @Test
  fun `apply ignores native agents inside selected pack but outside planned skill roots`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val unplannedSkillNativeAgents = fixture.repoRoot
      .resolve("platform-packs/kotlin/code-review/bill-kotlin-unplanned/native-agents")
    Files.createDirectories(unplannedSkillNativeAgents)
    Files.writeString(
      unplannedSkillNativeAgents.resolve("bill-kotlin-unplanned-worker.md"),
      """
      |---
      |name: bill-kotlin-unplanned-worker
      |---
      |
      |# Missing description in unplanned selected pack skill
      |
      """.trimMargin(),
    )
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    val nativeArtifactNames = result.nativeAgents.mapNotNull { native -> native.path?.fileName?.toString() }
    assertFalse(nativeArtifactNames.any { name -> "bill-kotlin-unplanned-worker" in name })
  }

  @Test
  fun `apply native-agent cache root ignores tampered plan staging root`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )
    val tampered = plan.copy(
      staging = plan.staging.copy(root = fixture.home.resolve("outside-installed-skills")),
    )

    val result = InstallOperations.applyInstall(tampered)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    result.nativeAgents
      .filter { native -> native.status == NativeAgentApplyStatus.LINKED }
      .mapNotNull { native -> native.path }
      .forEach { link ->
        val target = readSymlinkTarget(link)
        assertTrue(target.startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
        assertFalse(target.startsWith(fixture.home.resolve("outside-installed-skills")))
      }
  }

  @Test
  fun `apply fails when current source no longer matches planned staging intent`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
      ),
    )
    Files.writeString(
      fixture.repoRoot.resolve("skills/bill-code-review/content.md"),
      content("bill-code-review").replace("Test body.", "Changed after planning."),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(
      result.failures.any { failure ->
        failure.kind == InstallApplyIssueKind.STAGING_FAILED &&
          failure.skillName == "bill-code-review" &&
          failure.message.contains("Re-run planInstall")
      },
    )
    assertFalse(
      Files.exists(fixture.home.resolve("agent-skill-targets/codex/bill-code-review"), LinkOption.NOFOLLOW_LINKS),
      "stale plan should not install a link",
    )
    assertTrue(result.nativeAgents.isEmpty(), "stale skill staging should stop native-agent apply")
    assertFalse(
      Files.exists(fixture.home.resolve(".codex/agents/bill-code-review-worker.toml"), LinkOption.NOFOLLOW_LINKS),
      "stale plan should not install native-agent links",
    )
  }

  @Test
  fun `apply rejects unsafe skill link names without escaping target dir`() {
    val fixture = setupApplyFixture()
    seedBaseSkill(fixture.repoRoot, "bill-extra")
    Files.createDirectories(fixture.home.resolve(".codex"))
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
      ),
    )
    val unsafePlan = plan.copy(
      skills = plan.skills.map { skill ->
        if (skill.name == "bill-extra") skill.copy(name = "../victim") else skill
      },
      staging = plan.staging.copy(
        skillPaths = plan.staging.skillPaths.map { intent ->
          if (intent.skillName == "bill-extra") intent.copy(skillName = "../victim") else intent
        },
      ),
    )

    val result = InstallOperations.applyInstall(unsafePlan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(
      result.failures.any { failure ->
        failure.kind == InstallApplyIssueKind.SKILL_LINK_FAILED &&
          failure.skillName == "../victim" &&
          failure.message.contains("safe single path segment")
      },
    )
    assertFalse(
      Files.exists(fixture.home.resolve("agent-skill-targets/victim"), LinkOption.NOFOLLOW_LINKS),
      "unsafe skill name escaped the agent target dir",
    )
  }

  @Test
  fun `apply preserves existing user owned skill target files`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val userFile = targetDir.resolve("bill-code-review")
    Files.writeString(userFile, "user owned")
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertEquals("user owned", Files.readString(userFile))
    assertFalse(Files.isSymbolicLink(userFile), "user-owned file should not be replaced")
    assertTrue(
      result.skills
        .single { skill -> skill.skillName == "bill-code-review" }
        .links
        .any { link ->
          link.agent == InstallAgent.CODEX &&
            link.status == InstallAgentLinkStatus.FAILED &&
            link.message.contains("preserved")
        },
    )
  }

  @Test
  fun `apply preserves existing user owned skill target symlinks`() {
    val fixture = setupApplyFixture()
    Files.createDirectories(fixture.home.resolve(".codex"))
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val userTarget = Files.createTempFile("skillbill-user-symlink-target", ".md").also(tempDirs::add)
    val userLink = targetDir.resolve("bill-code-review")
    createSymlinkOrSkip(userLink, userTarget)
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertEquals(userTarget.toAbsolutePath().normalize(), readSymlinkTarget(userLink))
    assertTrue(
      result.skills
        .single { skill -> skill.skillName == "bill-code-review" }
        .links
        .any { link ->
          link.agent == InstallAgent.CODEX &&
            link.status == InstallAgentLinkStatus.FAILED &&
            link.message.contains("preserved")
        },
    )
  }

  @Test
  fun `apply surfaces Windows symlink warning state without parsing shell output`() {
    val fixture = setupApplyFixture()
    val plan = InstallOperations.planInstall(
      fixture.request(
        windowsSymlinkPreflight = WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.REQUIRES_ELEVATION_OR_DEVELOPER_MODE,
          decision = WindowsSymlinkDecision.PROCEED_WITH_SYMLINKS,
          message = "Windows symlink support was not confirmed.",
        ),
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.WARNING, result.status)
    assertEquals(WindowsSymlinkFallbackState.PROCEEDING, result.windowsSymlinkOutcome.fallbackState)
    assertTrue(result.skills.any { skill -> skill.staging.status == InstallSkillStagingStatus.STAGED })
    assertTrue(
      result.skills
        .flatMap { skill -> skill.links }
        .any { link -> link.status == InstallAgentLinkStatus.CREATED },
    )
    assertTrue(
      result.warnings.any { warning ->
        warning.message == "Windows symlink support was not confirmed." &&
          warning.guidance.orEmpty().contains("Developer Mode")
      },
    )
  }
}
