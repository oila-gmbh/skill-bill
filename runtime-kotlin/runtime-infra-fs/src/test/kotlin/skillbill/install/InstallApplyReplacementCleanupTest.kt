package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentLinkStatus
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyStatus
import java.nio.file.Files
import java.nio.file.LinkOption
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
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-kotlin-quality-check")))

    val baseOnlyReplacementPlan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        replaceExistingSkillBillLinks = true,
      ),
    )

    val result = InstallOperations.applyInstall(baseOnlyReplacementPlan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-review")))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-quality-check")))
    assertFalse(Files.exists(targetDir.resolve("bill-kotlin-code-review"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(targetDir.resolve("bill-kotlin-quality-check"), LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `apply replacement upgrades existing Skill Bill source symlinks before linking staged skills`() {
    val fixture = setupApplyFixture()
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val legacySourceLink = targetDir.resolve("bill-code-review")
    createSymlinkOrSkip(legacySourceLink, fixture.repoRoot.resolve("skills/bill-code-review"))
    val legacyManagedDir = targetDir.resolve("bill-quality-check")
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
  fun `apply replacement removes legacy renamed Skill Bill links`() {
    val fixture = setupApplyFixture()
    val targetDir = fixture.home.resolve("agent-skill-targets/codex")
    Files.createDirectories(targetDir)
    val legacySourceLink = targetDir.resolve("bill-backend-kotlin-code-review")
    createSymlinkOrSkip(
      legacySourceLink,
      fixture.repoRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review"),
    )
    val legacyManagedDir = targetDir.resolve("mdp-gcheck")
    Files.createDirectories(legacyManagedDir)
    Files.writeString(legacyManagedDir.resolve(".skill-bill-install"), "")
    Files.writeString(legacyManagedDir.resolve("SKILL.md"), "old managed install")
    val featureImplementLink = targetDir.resolve("bill-feature-implement")
    createSymlinkOrSkip(
      featureImplementLink,
      fixture.repoRoot.resolve("skills/bill-feature-task"),
    )
    val agenticFeatureImplementLink = targetDir.resolve("bill-feature-implement-agentic")
    createSymlinkOrSkip(
      agenticFeatureImplementLink,
      fixture.repoRoot.resolve("skills/bill-feature-task"),
    )
    val skillScaffoldManagedDir = targetDir.resolve("mdp-skill-scaffold")
    Files.createDirectories(skillScaffoldManagedDir)
    Files.writeString(skillScaffoldManagedDir.resolve(".skill-bill-install"), "")
    Files.writeString(skillScaffoldManagedDir.resolve("SKILL.md"), "old managed install")
    val newSkillAllAgentsLink = targetDir.resolve("bill-new-skill-all-agents")
    createSymlinkOrSkip(
      newSkillAllAgentsLink,
      fixture.repoRoot.resolve("skills/bill-create-skill"),
    )
    val grillPlanLink = targetDir.resolve("bill-grill-plan")
    createSymlinkOrSkip(
      grillPlanLink,
      fixture.repoRoot.resolve("skills/bill-grill-plan"),
    )
    val skillRemoveLink = targetDir.resolve("bill-skill-remove")
    createSymlinkOrSkip(
      skillRemoveLink,
      fixture.repoRoot.resolve("skills/bill-skill-remove"),
    )
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        replaceExistingSkillBillLinks = true,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertFalse(Files.exists(legacySourceLink, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(legacyManagedDir, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(featureImplementLink, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(agenticFeatureImplementLink, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(skillScaffoldManagedDir, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(newSkillAllAgentsLink, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(grillPlanLink, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(skillRemoveLink, LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-code-review")))
    assertTrue(Files.isSymbolicLink(targetDir.resolve("bill-quality-check")))
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
