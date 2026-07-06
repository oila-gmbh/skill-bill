package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyStatus
import skillbill.install.runtime.InstallOperations
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallApplyReplacementCleanupTest : InstallApplyTestSupport() {
  @Test
  fun `apply replacement removes previously installed unselected platform links`() {
    val fixture = setupApplyFixture()
    val selectedPlatformPlan = InstallOperations.planInstall(
      fixture.request(
        selectedPlatforms = setOf("kotlin"),
        agents = setOf(InstallAgent.CODEX),
      ),
    )
    InstallOperations.applyInstall(selectedPlatformPlan)
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-kotlin-code-review")))
    assertFalse(Files.exists(targetDir.resolve("bill-kotlin-code-check"), LinkOption.NOFOLLOW_LINKS))
    assertTrue(
      Files.isRegularFile(readSymlinkTarget(targetDir.resolve("bill-code-check")).resolve("bill-kotlin-code-check.md")),
    )

    val baseOnlyReplacementPlan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        replaceExistingSkillBillLinks = true,
      ),
    )

    val result = InstallOperations.applyInstall(baseOnlyReplacementPlan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-review")))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-check")))
    assertFalse(Files.exists(targetDir.resolve("bill-kotlin-code-review"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(targetDir.resolve("bill-kotlin-code-check"), LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `apply replacement upgrades existing Skill Bill source symlinks before linking staged skills`() {
    val fixture = setupApplyFixture()
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val legacySourceLink = targetDir.resolve("bill-code-review")
    createSymlinkOrSkip(legacySourceLink, fixture.repoRoot.resolve("skills/bill-code-review"))
    val legacyManagedDir = targetDir.resolve("bill-code-check")
    Files.createDirectories(legacyManagedDir)
    Files.writeString(legacyManagedDir.resolve(".skill-bill-install"), "")
    Files.writeString(legacyManagedDir.resolve("SKILL.md"), "old managed install")
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        replaceExistingSkillBillLinks = true,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(Files.isSymbolicLink(legacySourceLink))
    assertTrue(readSymlinkTarget(legacySourceLink).startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
    assertTrue(Files.isSymbolicLink(legacyManagedDir))
    assertTrue(readSymlinkTarget(legacyManagedDir).startsWith(fixture.home.resolve(".skill-bill/installed-skills")))
    val billCodeReviewLinks = result.skills
      .single { skill -> skill.skillName == "bill-code-review" }
      .links
    assertTrue(
      billCodeReviewLinks.any { link ->
        link.agent == InstallAgent.CODEX && link.status == InstallAgentLinkStatus.CREATED
      },
    )
  }

  @Test
  fun `apply migrates a clone-pointing managed link onto the copy with no dangling clone link`() {
    // SKILL-76 AC-10: a pre-existing repo-symlinked install points its managed agent links into the
    // fetched CLONE (a path OUTSIDE ~/.skill-bill). The first copied-source install must repoint that
    // link UNDER the copied repoRoot's staging cache and leave NO symlink still resolving into the
    // clone. The clone here is a sibling dir that is never injected as repoRoot, modelling the real
    // "clone deleted after install" guarantee: nothing may keep pointing at it.
    val fixture = setupApplyFixture()
    val clone = Files.createTempDirectory("skillbill-install-apply-clone").also(tempDirs::add)
    seedBaseSkill(clone, "bill-code-review")
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val cloneManagedLink = targetDir.resolve("bill-code-review")
    createSymlinkOrSkip(cloneManagedLink, clone.resolve("skills/bill-code-review"))

    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        replaceExistingSkillBillLinks = true,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(Files.isSymbolicLink(cloneManagedLink), "managed link must survive as a symlink")
    val repointed = readSymlinkTarget(cloneManagedLink)
    assertTrue(
      repointed.startsWith(fixture.home.resolve(".skill-bill/installed-skills")),
      "managed link must repoint under the copied repoRoot staging cache, was $repointed",
    )
    assertFalse(
      repointed.startsWith(clone.toAbsolutePath().normalize()),
      "managed link must NOT keep resolving into the clone, was $repointed",
    )
    // No surviving link anywhere in the agent target dir resolves into the clone.
    val cloneRoot = clone.toAbsolutePath().normalize()
    val danglingIntoClone = Files.list(targetDir).use { stream ->
      stream
        .filter { entry -> Files.isSymbolicLink(entry) }
        .filter { entry -> readSymlinkTarget(entry).startsWith(cloneRoot) }
        .toList()
    }
    assertTrue(danglingIntoClone.isEmpty(), "no symlink may still point into the clone: $danglingIntoClone")
  }

  @Test
  fun `apply replacement removes legacy renamed Skill Bill links`() {
    val fixture = setupApplyFixture()
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val legacyPaths = createLegacyRenamedLinks(fixture, targetDir)
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        replaceExistingSkillBillLinks = true,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    legacyPaths.forEach { path ->
      assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS), "$path should be removed")
    }
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-review")))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-check")))
  }

  private fun createLegacyRenamedLinks(fixture: ApplyFixture, targetDir: Path): List<Path> {
    val links = listOf(
      "bill-backend-kotlin-code-review" to "platform-packs/kotlin/code-review/bill-kotlin-code-review",
      "bill-quality-check" to "skills/bill-code-check",
      "bill-code-quality-check" to "skills/bill-code-check",
      "bill-kotlin-quality-check" to "platform-packs/kotlin/quality-check/bill-kotlin-code-check",
      "bill-kotlin-code-quality-check" to "platform-packs/kotlin/quality-check/bill-kotlin-code-check",
      ("bill-feature-" + "implement") to "skills/bill-feature-task",
      ("bill-feature-" + "implement-agentic") to "skills/bill-feature-task",
      "bill-new-skill-all-agents" to "skills/bill-create-skill",
      "bill-grill-plan" to "skills/bill-grill-plan",
      "bill-skill-remove" to "skills/bill-skill-remove",
    ).map { (linkName, sourcePath) ->
      targetDir.resolve(linkName).also { link ->
        createSymlinkOrSkip(link, fixture.repoRoot.resolve(sourcePath))
      }
    }
    val managedDirs = listOf(
      targetDir.resolve("mdp-gcheck"),
      targetDir.resolve("mdp-skill-scaffold"),
    ).onEach { managedDir ->
      Files.createDirectories(managedDir)
      Files.writeString(managedDir.resolve(".skill-bill-install"), "")
      Files.writeString(managedDir.resolve("SKILL.md"), "old managed install")
    }
    return links + managedDirs
  }

  @Test
  fun `apply surfaces replacement cleanup failures as structured link failures`() {
    val fixture = setupApplyFixture()
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        replaceExistingSkillBillLinks = true,
      ),
    )
    val invalidName = "bad\u0000name"
    val tampered = plan.copy(
      skills = plan.skills.map { skill ->
        if (skill.name == "bill-code-review") skill.copy(name = invalidName) else skill
      },
    )

    val result = InstallOperations.applyInstall(tampered)

    assertEquals(InstallApplyStatus.FAILURE, result.status)
    assertTrue(
      result.failures.any { failure ->
        failure.kind == InstallApplyIssueKind.SKILL_LINK_FAILED &&
          failure.agent == InstallAgent.CODEX &&
          failure.path == targetDir &&
          failure.message.contains("Nul character")
      },
    )
  }
}
