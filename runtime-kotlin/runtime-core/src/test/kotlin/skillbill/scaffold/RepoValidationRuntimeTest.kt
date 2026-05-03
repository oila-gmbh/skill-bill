package skillbill.scaffold

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepoValidationRuntimeTest {
  @Test
  fun `release refs preserve semver metadata`() {
    val stable = RepoValidationRuntime.parseReleaseRef("refs/tags/v1.2.3")
    assertEquals("v1.2.3", stable.tag)
    assertEquals("1.2.3", stable.version)
    assertFalse(stable.prerelease)

    val prerelease = RepoValidationRuntime.parseReleaseRef("v2.0.0-rc.1+build.5")
    assertEquals("v2.0.0-rc.1+build.5", prerelease.tag)
    assertEquals("2.0.0-rc.1+build.5", prerelease.version)
    assertTrue(prerelease.prerelease)
  }

  @Test
  fun `release refs reject non semver tags`() {
    val error = kotlin.runCatching {
      RepoValidationRuntime.parseReleaseRef("release-1.0")
    }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
    assertTrue(error.message.orEmpty().contains("Release tag must match"))
  }

  @Test
  fun `repo validation reports missing governed directories`() {
    val repoRoot = Files.createTempDirectory("skillbill-empty-repo")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(report.issues.any { it.contains("skills/ directory is missing") })
    assertTrue(report.issues.any { it.contains("README.md is missing") })
  }

  @Test
  fun `repo validation rejects add-ons outside platform pack ownership`() {
    val repoRoot = Files.createTempDirectory("skillbill-bad-addon")
    val addonFile = repoRoot.resolve("skills/example/addons/bad-addon.md")
    Files.createDirectories(addonFile.parent)
    Files.writeString(addonFile, "# Bad add-on\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/example/addons/bad-addon.md") &&
          it.contains("platform-packs/<pack>/addons/")
      },
    )
  }

  @Test
  fun `repo validation rejects missing required skill sidecars`() {
    val repoRoot = Files.createTempDirectory("skillbill-missing-sidecar")
    createRepoValidationSkillFixture(repoRoot, skipSidecar = "review-scope.md")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("required supporting sidecar 'review-scope.md' is missing beside the skill")
      },
    )
  }

  @Test
  fun `repo validation rejects supporting sidecars pointing to the wrong target`() {
    val repoRoot = Files.createTempDirectory("skillbill-wrong-sidecar")
    val wrongTarget = repoRoot.resolve("orchestration/wrong/PLAYBOOK.md")
    Files.createDirectories(wrongTarget.parent)
    Files.writeString(wrongTarget, "wrong\n")
    createRepoValidationSkillFixture(repoRoot, overrideTargets = mapOf("review-scope.md" to wrongTarget))

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("supporting sidecar 'review-scope.md' points to") &&
          it.contains("instead of orchestration/review-scope/PLAYBOOK.md")
      },
      report.issues.joinToString("\n"),
    )
  }

  private fun createRepoValidationSkillFixture(
    repoRoot: java.nio.file.Path,
    skipSidecar: String? = null,
    overrideTargets: Map<String, java.nio.file.Path> = emptyMap(),
  ) {
    supportingFileTargets(repoRoot).values.forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "contract\n")
    }
    val skillDir = repoRoot.resolve("skills/bill-code-review")
    Files.createDirectories(skillDir)
    val requiredFiles = requiredSupportingFilesForSkill("bill-code-review")
    Files.writeString(
      skillDir.resolve("SKILL.md"),
      """
      ---
      name: bill-code-review
      description: Review code.
      ---
      ## Descriptor
      ${requiredFiles.joinToString(" ")}
      ## Execution
      Run the review.
      ## Ceremony
      Report findings.
      """.trimIndent(),
    )
    val targets = supportingFileTargets(repoRoot)
    requiredFiles.filterNot { it == skipSidecar }.forEach { fileName ->
      val sidecar = skillDir.resolve(fileName)
      val target = overrideTargets[fileName] ?: targets.getValue(fileName)
      Files.createSymbolicLink(sidecar, sidecar.parent.relativize(target))
    }
  }
}
