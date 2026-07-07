package skillbill.scaffold

import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.renderNativeAgentSource
import skillbill.scaffold.runtime.RepoValidationRuntime
import skillbill.scaffold.runtime.requiredSupportingFilesForSkill
import skillbill.scaffold.runtime.supportingFileTargets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("LargeClass")
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
  fun `release refs accept bare version tags without v prefix`() {
    val stable = RepoValidationRuntime.parseReleaseRef("0.2.0")
    assertEquals("0.2.0", stable.tag)
    assertEquals("0.2.0", stable.version)
    assertFalse(stable.prerelease)

    val prerelease = RepoValidationRuntime.parseReleaseRef("refs/tags/1.0.0-rc.1")
    assertEquals("1.0.0-rc.1", prerelease.tag)
    assertEquals("1.0.0-rc.1", prerelease.version)
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
  fun `repo validation rejects feature addon pointer without manifest usage declaration`() {
    val repoRoot = Files.createTempDirectory("skillbill-undeclared-feature-addon")
    createRepoValidationSkillFixture(repoRoot)
    val packRoot = repoRoot.resolve("platform-packs/kmp")
    Files.createDirectories(packRoot.resolve("addons"))
    Files.writeString(packRoot.resolve("addons/android-compose-implementation.md"), "# Android Compose\n")
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      platform: kmp
      contract_version: "1.2"
      routing_signals:
        strong: ["androidMain"]
      declared_code_review_areas: []
      pointers:
        feature-task:
          - name: android-compose-implementation.md
            target: platform-packs/kmp/addons/android-compose-implementation.md
      """.trimIndent(),
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/kmp/platform.yaml") &&
          it.contains("feature_addon_usage.feature-task") &&
          it.contains("android-compose-implementation.md")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation does not require generated supporting pointers beside non-platform skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-missing-sidecar")
    createRepoValidationSkillFixture(repoRoot)

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(
      report.issues.any { it.contains("required supporting sidecar") || it.contains("supporting sidecar") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects extra authored files beside source skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-extra-source-file")
    createRepoValidationSkillFixture(repoRoot)
    Files.writeString(repoRoot.resolve("skills/bill-code-review/patterns.md"), "extra organization file\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-code-review/patterns.md") &&
          it.contains("skill source directories may contain only content.md and native-agents/")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects committed generated supporting pointer beside non-platform skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-wrong-sidecar")
    val wrongTarget = repoRoot.resolve("orchestration/wrong/PLAYBOOK.md")
    Files.createDirectories(wrongTarget.parent)
    Files.writeString(wrongTarget, "wrong\n")
    createRepoValidationSkillFixture(
      repoRoot,
      overrideTargets = mapOf("review-scope.md" to wrongTarget),
      writeSidecars = true,
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any {
        it.contains("skills/bill-code-review/review-scope.md") &&
          it.contains("committed generated supporting pointer file is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects git symlink placeholders beside non-platform skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-placeholder-sidecar")
    createRepoValidationSkillFixture(repoRoot, sidecarMode = SidecarMode.GitPlaceholder, writeSidecars = true)

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any {
        it.contains("skills/bill-code-review/review-scope.md") &&
          it.contains("committed generated supporting pointer file is not allowed")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects regular copied generated supporting pointer files beside non-platform skills`() {
    val repoRoot = Files.createTempDirectory("skillbill-regular-sidecar")
    createRepoValidationSkillFixture(repoRoot)
    val sidecar = repoRoot.resolve("skills/bill-code-review/review-scope.md")
    Files.writeString(sidecar, "copied markdown\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any {
        it.contains("skills/bill-code-review/review-scope.md") &&
          it.contains("committed generated supporting pointer file is not allowed")
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
  fun `repo validation preserves native agent source files`() {
    val repoRoot = Files.createTempDirectory("skillbill-native-agent-source-preservation")
    createRepoValidationSkillFixture(repoRoot)
    val nativeAgent = repoRoot.resolve("skills/bill-code-review/native-agents/bill-code-review-worker.md")
    Files.createDirectories(nativeAgent.parent)
    val sourceText = renderNativeAgentSource(
      NativeAgentSource(
        name = "bill-code-review-worker",
        description = "Review changed code.",
        body = "Review the changed files.",
      ),
    )
    Files.writeString(nativeAgent, sourceText)

    RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(Files.exists(nativeAgent), "repo validation must not delete native-agent source files")
    assertEquals(
      sourceText,
      Files.readString(nativeAgent),
      "repo validation must not rewrite native-agent source files",
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

  // ---------------------------------------------------------------------------------------------
  // F-T1 (testing): orphan-path detection in discoverSkillFiles / discoverPlatformPackSkillFiles.
  // The four assertions below pin the iter-1 fixes for F-E so a regression that drops the issue,
  // mis-formats the message, or routes the wrong path into seenContent is caught here.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `repo validation accepts skills content_md without sibling SKILL_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orphan-skills-content")
    val orphanDir = repoRoot.resolve("skills/bill-orphan-content")
    Files.createDirectories(orphanDir)
    Files.writeString(
      orphanDir.resolve("content.md"),
      """
      ---
      name: bill-orphan-content
      description: Authored content with no sibling generated wrapper.
      ---

      # Orphan Content

      Body that exists without a generated wrapper SKILL.md.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.issues.any { it.contains("content.md found without sibling SKILL.md") })
  }

  @Test
  fun `repo validation reports skills SKILL_md without sibling content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orphan-skills-wrapper")
    val orphanDir = repoRoot.resolve("skills/bill-orphan-wrapper")
    Files.createDirectories(orphanDir)
    Files.writeString(
      orphanDir.resolve("SKILL.md"),
      """
      ---
      name: bill-orphan-wrapper
      description: Wrapper without a sibling content.md to surface the reverse orphan.
      ---
      ## Descriptor
      Stub.
      ## Execution
      Stub.
      ## Ceremony
      Stub.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-orphan-wrapper") &&
          it.contains("SKILL.md found without sibling content.md")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation accepts platform pack content_md without sibling SKILL_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orphan-pack-content")
    val orphanDir = repoRoot.resolve("platform-packs/orphanpack/code-review/bill-orphanpack-code-review")
    Files.createDirectories(orphanDir)
    Files.writeString(
      orphanDir.resolve("content.md"),
      """
      ---
      name: bill-orphanpack-code-review
      description: Pack-scoped authored content without a sibling wrapper.
      ---

      # Orphan Pack Content

      Body to surface the platform-packs/ orphan branch.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.issues.any { it.contains("content.md found without sibling SKILL.md") })
  }

  @Test
  fun `repo validation reports platform pack SKILL_md without sibling content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orphan-pack-wrapper")
    val orphanDir = repoRoot.resolve("platform-packs/orphanpack/code-review/bill-orphanpack-code-review")
    Files.createDirectories(orphanDir)
    Files.writeString(
      orphanDir.resolve("SKILL.md"),
      """
      ---
      name: bill-orphanpack-code-review
      description: Pack-scoped wrapper without a sibling content.md.
      ---
      ## Descriptor
      Stub.
      ## Execution
      Stub.
      ## Ceremony
      Stub.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/orphanpack/code-review/bill-orphanpack-code-review") &&
          it.contains("SKILL.md found without sibling content.md")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation reports content_md frontmatter name mismatch`() {
    // Regression for M-2 (architecture iter-2 F-002): an authored content.md whose frontmatter
    // `name:` disagrees with its parent directory must surface as a content.md issue, not as a
    // wrapper drift symptom only after `skill-bill render` regenerates SKILL.md.
    val repoRoot = Files.createTempDirectory("skillbill-content-name-mismatch")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/bill-code-review/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: bill-wrong-name
      description: Authored content whose name disagrees with the directory.
      ---

      # Code Review Content

      Authored review guidance for the code-review baseline skill fixture.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("bill-wrong-name") &&
          it.contains("bill-code-review")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects content_md without authored guidance beyond title`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-empty-body")
    createRepoValidationSkillFixture(repoRoot)
    // Use a horizontal skill name — no class declares sections for it, so authored body is required.
    val contentFile = repoRoot.resolve("skills/bill-horizontal-fixture/content.md")
    Files.createDirectories(contentFile.parent)
    Files.writeString(
      contentFile,
      """
      ---
      name: bill-horizontal-fixture
      description: Horizontal fixture with no body.
      ---

      # Horizontal Fixture
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("must include authored guidance beyond the title heading")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects unresolved placeholders in content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-placeholder")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/bill-code-review/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: bill-code-review
      description: Review code.
      ---

      # Code Review Content

      TODO: replace this placeholder.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("unresolved TODO/FIXME placeholder")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects generated support pointer links in content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-ceremony-pointer")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/bill-code-review/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: bill-code-review
      description: Review code.
      ---

      # Code Review Content

      Authored review guidance.

      For the shared telemetry contract, follow [telemetry-contract.md](telemetry-contract.md).
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("generated support pointer 'telemetry-contract.md'")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects subagent runtime notes heading in content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-subagent-notes")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/bill-code-review/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: bill-code-review
      description: Review code.
      ---

      # Code Review Content

      Authored review guidance.

      ## Subagent Spawn Runtime Notes

      Prose that belongs in the wrapper, not in content.md.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("auto-generated subagent runtime notes heading")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects generated wrapper boilerplate headings in content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-content-wrapper-boilerplate")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/bill-code-review/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: bill-code-review
      description: Review code.
      ---

      # Code Review Content

      Authored review guidance.

      ## Descriptor

      Generated wrapper metadata does not belong in source content.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains(contentFile.toString()) &&
          it.contains("generated wrapper boilerplate heading '## Descriptor'")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation does not require governed SKILL_md drift files on disk`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-drift-wiring")
    val skillDir = repoRoot.resolve("skills/bill-runtime-drift")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-runtime-drift
      description: Runtime drift wiring fixture.
      ---

      # Runtime Drift Fixture
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.issues.any { it.contains("governed SKILL.md output drifted") }, report.issues.joinToString("\n"))
  }

  @Test
  fun `repo validation includes generated artifact guard issues`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-guard-wiring")
    createRepoValidationSkillFixture(repoRoot)
    val generatedSkillDir = repoRoot.resolve("skills/bill-new-generated")
    Files.createDirectories(generatedSkillDir)
    Files.writeString(
      generatedSkillDir.resolve("content.md"),
      """
      ---
      name: bill-new-generated
      description: New generated wrapper fixture.
      ---

      # New Generated Fixture
      """.trimIndent() + "\n",
    )
    Files.writeString(generatedSkillDir.resolve("SKILL.md"), "generated wrapper\n")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-new-generated/SKILL.md") &&
          it.contains("committed governed SKILL.md output is not allowed")
      },
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

  @Test
  fun `repo validation rejects bare orchestration path token in skill content_md`() {
    val repoRoot = Files.createTempDirectory("skillbill-orchestration-path-in-skill")
    createRepoValidationSkillFixture(repoRoot)
    val contentFile = repoRoot.resolve("skills/bill-code-review/content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: bill-code-review
      description: Review code.
      ---

      # Code Review Content

      Authored review guidance.

      See `orchestration/contracts/some-schema.yaml` for detail.
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any {
        it.contains("skills/bill-code-review/content.md") &&
          it.contains("orchestration/contracts/some-schema.yaml")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation passes when skill content_md contains no bare orchestration paths`() {
    val repoRoot = Files.createTempDirectory("skillbill-no-orchestration-path-in-skill")
    createRepoValidationSkillFixture(repoRoot)

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(
      report.issues.any { it.contains("must not reference bare orchestration path") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation applies portable review wording lint to non kotlin pack baselines from manifests`() {
    val repoRoot = Files.createTempDirectory("skillbill-portable-review-python")
    createRepoValidationSkillFixture(repoRoot)
    seedPlatformReviewPack(
      repoRoot = repoRoot,
      slug = "python",
      body = "Agent to spawn: security reviewer.",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any {
        it.contains("platform-packs/python/code-review/bill-python-code-review/content.md") &&
          it.contains("must use portable specialist-review wording")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation keeps portable review wording lint for kotlin and kmp manifest baselines`() {
    val repoRoot = Files.createTempDirectory("skillbill-portable-review-kotlin-kmp")
    createRepoValidationSkillFixture(repoRoot)
    seedPlatformReviewPack(
      repoRoot = repoRoot,
      slug = "kotlin",
      body = "Agent to spawn: security reviewer.",
    )
    seedPlatformReviewPack(
      repoRoot = repoRoot,
      slug = "kmp",
      body = "sub-agent review lane.",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any {
        it.contains("platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md") &&
          it.contains("must use portable specialist-review wording")
      },
      report.issues.joinToString("\n"),
    )
    assertTrue(
      report.issues.any {
        it.contains("platform-packs/kmp/code-review/bill-kmp-code-review/content.md") &&
          it.contains("must not describe review delegation as sub-agents")
      },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation accepts a valid internal-for declaration`() {
    val repoRoot = Files.createTempDirectory("skillbill-valid-internal")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature", null)
    seedInternalSkill(repoRoot, "bill-feature-task", "bill-feature")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    val internalIssues = report.issues.filter { it.contains("internal skill") }
    assertTrue(internalIssues.isEmpty(), "expected no internal-skill issues; got: ${internalIssues.joinToString("\n")}")
  }

  @Test
  fun `repo validation rejects internal-for with unknown parent`() {
    val repoRoot = Files.createTempDirectory("skillbill-unknown-parent")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature-task", "bill-featur")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any { it.contains("not a discovered skill") && it.contains("bill-feature-task") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects internal-for with self parent`() {
    val repoRoot = Files.createTempDirectory("skillbill-self-parent")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature-task", "bill-feature-task")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any { it.contains("skill itself") && it.contains("bill-feature-task") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects chained internal-for`() {
    val repoRoot = Files.createTempDirectory("skillbill-chained")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-other", null)
    seedInternalSkill(repoRoot, "bill-feature", "bill-other")
    seedInternalSkill(repoRoot, "bill-feature-task", "bill-feature")

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any { it.contains("chained internal-for") && it.contains("bill-feature") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation rejects an authored sidecar collision with the internal name`() {
    val repoRoot = Files.createTempDirectory("skillbill-collision")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature", null)
    seedInternalSkill(repoRoot, "bill-feature-task", "bill-feature")
    Files.writeString(
      repoRoot.resolve("skills/bill-feature/bill-feature-task.md"),
      "authored collision\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertTrue(
      report.issues.any { it.contains("collides") && it.contains("bill-feature-task.md") },
      report.issues.joinToString("\n"),
    )
  }

  @Test
  fun `repo validation does not require internal skills in the README catalog`() {
    val repoRoot = Files.createTempDirectory("skillbill-readme-internal")
    createRepoValidationSkillFixture(repoRoot)
    seedInternalSkill(repoRoot, "bill-feature", null)
    seedInternalSkill(repoRoot, "bill-feature-task", "bill-feature")
    // README catalog lists the listed skills but intentionally omits the internal one.
    Files.writeString(
      repoRoot.resolve("README.md"),
      """
      | Skill | Purpose |
      |-------|---------|
      | `/bill-code-review` | Review code |
      | `/bill-feature` | Feature entry |
      """.trimIndent() + "\n",
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    val readmeCatalogIssues = report.issues.filter { it.contains("README.md catalog is missing skills") }
    val detail = readmeCatalogIssues.joinToString("\n")
    assertTrue(
      readmeCatalogIssues.isEmpty(),
      "internal skills must be excluded from the README catalog requirement; got: $detail",
    )
  }

  private fun seedInternalSkill(repoRoot: java.nio.file.Path, name: String, internalFor: String?) {
    val skillDir = repoRoot.resolve("skills/$name")
    Files.createDirectories(skillDir)
    val frontmatter = buildString {
      appendLine("---")
      appendLine("name: $name")
      appendLine("description: $name skill.")
      if (internalFor != null) {
        appendLine("internal-for: $internalFor")
      }
      appendLine("---")
    }
    Files.writeString(skillDir.resolve("content.md"), frontmatter + "\nAuthored body.\n")
  }

  private fun seedPlatformReviewPack(repoRoot: java.nio.file.Path, slug: String, body: String) {
    val skillName = "bill-$slug-code-review"
    val contentFile = repoRoot.resolve("platform-packs/$slug/code-review/$skillName/content.md")
    Files.createDirectories(contentFile.parent)
    Files.writeString(
      repoRoot.resolve("platform-packs/$slug/platform.yaml"),
      """
      |platform: $slug
      |contract_version: "1.2"
      |display_name: $slug
      |routing_signals:
      |  strong:
      |    - $slug
      |  tie_breakers: []
      |declared_code_review_areas: []
      |declared_files:
      |  baseline: code-review/$skillName/content.md
      |  areas: {}
      |area_metadata: {}
      |
      """.trimMargin(),
    )
    Files.writeString(
      contentFile,
      """
      |---
      |name: $skillName
      |description: Review $slug code.
      |---
      |
      |$body
      |
      """.trimMargin(),
    )
  }

  private fun createRepoValidationSkillFixture(
    repoRoot: java.nio.file.Path,
    skipSidecar: String? = null,
    overrideTargets: Map<String, java.nio.file.Path> = emptyMap(),
    sidecarMode: SidecarMode = SidecarMode.SymbolicLink,
    writeSidecars: Boolean = false,
  ) {
    skillbill.testsupport.SkillClassFixtures.seedShippedSkillClasses(repoRoot)
    supportingFileTargets(repoRoot).values.forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "contract\n")
    }
    val skillDir = repoRoot.resolve("skills/bill-code-review")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: bill-code-review
      description: Review code.
      ---

      # Code Review Content

      Authored review guidance for the code-review baseline skill fixture.
      """.trimIndent(),
    )
    if (!writeSidecars) {
      return
    }
    val targets = supportingFileTargets(repoRoot)
    requiredSupportingFilesForSkill("bill-code-review", repoRoot).filterNot { it == skipSidecar }.forEach { fileName ->
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
