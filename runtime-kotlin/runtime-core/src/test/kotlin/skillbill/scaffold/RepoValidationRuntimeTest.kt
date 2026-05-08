package skillbill.scaffold

import skillbill.nativeagent.NativeAgentSource
import skillbill.nativeagent.renderNativeAgentSource
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

  @Test
  fun `repo validation accepts git symlink placeholder sidecars`() {
    val repoRoot = Files.createTempDirectory("skillbill-placeholder-sidecar")
    createRepoValidationSkillFixture(repoRoot, sidecarMode = SidecarMode.GitPlaceholder)

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.issues.any { it.contains("must be a symlink") }, report.issues.joinToString("\n"))
    assertFalse(report.issues.any { it.contains("points to") }, report.issues.joinToString("\n"))
  }

  @Test
  fun `repo validation rejects regular sidecars that are not git symlink placeholders`() {
    val repoRoot = Files.createTempDirectory("skillbill-regular-sidecar")
    createRepoValidationSkillFixture(repoRoot)
    val sidecar = repoRoot.resolve("skills/bill-code-review/review-scope.md")
    Files.delete(sidecar)
    Files.writeString(sidecar, "copied markdown\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("required supporting sidecar 'review-scope.md' must be a symlink")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation skips native agent markdown skill references`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-refs")
    createRepoValidationSkillFixture(repoRoot)
    val nativeAgent = repoRoot.resolve("skills/bill-code-review/native-agents/bill-code-review-worker.md")
    Files.createDirectories(nativeAgent.parent)
    Files.writeString(
      nativeAgent,
      renderNativeAgentSource(
        NativeAgentSource(
          name = "bill-code-review-worker",
          description = "Review changed code.",
          body = "Mentions bill-code-review-worker inside native agent prose.",
        ),
      ),
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(
      report.issues.any { it.contains("references unknown skill 'bill-code-review-worker'") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects checked-in generated native agent artifact with source`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-checked-in-artifact")
    createRepoValidationSkillFixture(repoRoot)
    writeNativeAgentFixture(repoRoot.resolve("skills/bill-code-review"), "bill-code-review-worker")
    val generatedArtifact = repoRoot.resolve("skills/bill-code-review/opencode-agents/bill-code-review-worker.md")
    Files.createDirectories(generatedArtifact.parent)
    Files.writeString(generatedArtifact, "checked-in generated file\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("opencode-agents/bill-code-review-worker.md") &&
          it.contains("must not be checked in")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects checked-in generated native agent artifact without source`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-orphan")
    createRepoValidationSkillFixture(repoRoot)
    val orphan = repoRoot.resolve("skills/bill-code-review/codex-agents/orphan.toml")
    Files.createDirectories(orphan.parent)
    Files.writeString(orphan, "name = \"orphan\"\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { it.contains("codex-agents/orphan.toml") && it.contains("must not be checked in") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects checked-in generated junie native agent artifact`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-junie-checked-in")
    createRepoValidationSkillFixture(repoRoot)
    writeNativeAgentFixture(repoRoot.resolve("skills/bill-code-review"), "bill-code-review-worker")
    val generatedJunie = repoRoot.resolve("skills/bill-code-review/junie-agents/bill-code-review-worker.md")
    Files.createDirectories(generatedJunie.parent)
    Files.writeString(generatedJunie, "checked-in junie file\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("junie-agents/bill-code-review-worker.md") &&
          it.contains("must not be checked in")
      },
      report.issues.joinToString("\n"),
    )
  }

  private fun createRepoValidationSkillFixture(
    repoRoot: java.nio.file.Path,
    skipSidecar: String? = null,
    overrideTargets: Map<String, java.nio.file.Path> = emptyMap(),
    sidecarMode: SidecarMode = SidecarMode.SymbolicLink,
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
      val relativeTarget = sidecar.parent.relativize(target).toString()
      when (sidecarMode) {
        SidecarMode.SymbolicLink -> Files.createSymbolicLink(sidecar, java.nio.file.Path.of(relativeTarget))
        SidecarMode.GitPlaceholder -> Files.writeString(sidecar, relativeTarget)
      }
    }
  }

  private fun writeNativeAgentFixture(skillDir: java.nio.file.Path, name: String) {
    val source = NativeAgentSource(name = name, description = "Review changed code.", body = "# Worker\n\nReview it.")
    val sourcePath = skillDir.resolve("native-agents/$name.md")
    Files.createDirectories(sourcePath.parent)
    Files.writeString(sourcePath, renderNativeAgentSource(source))
  }

  private enum class SidecarMode {
    SymbolicLink,
    GitPlaceholder,
  }
}
