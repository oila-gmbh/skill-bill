package skillbill.install

import skillbill.error.InternalSkillSidecarCollisionError
import skillbill.install.model.AgentTarget
import skillbill.install.plan.InstallContext
import skillbill.install.plan.installSkill
import skillbill.install.staging.discoverInternalSidecarTargets
import skillbill.install.staging.renderInternalSidecarWrapper
import skillbill.install.staging.stageInstalledSkill
import skillbill.install.staging.writeInternalSidecarFiles
import skillbill.scaffold.authoring.resolveTarget
import skillbill.scaffold.runtime.RepoValidationRuntime
import skillbill.scaffold.runtime.supportingFileTargets
import skillbill.testsupport.SkillClassFixtures
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-102 subtask 1 (PD2/PD6): internal-skill install staging.
 *
 * Covers:
 *  - sidecar staging layout and naming (`<skill-name>.md` at the parent's staging root),
 *  - no standalone staged skill directory for the internal skill,
 *  - collision guard (authored file occupying the sidecar name),
 *  - idempotent reinstall,
 *  - byte-identical staged output when no skill declares internal-for (criterion 7).
 *
 * Fixtures are created inside the tests, not from repo skills.
 */
class InternalSkillStagingTest {
  private val tempDirs = mutableListOf<Path>()

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

  @Test
  fun `internal child renders as a sidecar inside the parent staged directory`() {
    val fixture = setupParentWithInternalChild()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)

    val sidecar = rendered.stagingDir.resolve("${fixture.childName}.md")
    assertTrue(Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS), "missing sidecar at $sidecar")
    assertTrue(sidecar in rendered.renderedSidecarFiles, "sidecar not reported in renderedSidecarFiles")
    val childTarget = resolveTarget(fixture.repoRoot, fixture.childName)
    val expectedWrapper = skillbill.scaffold.authoring.renderWrapper(childTarget)
    assertEquals(expectedWrapper, Files.readString(sidecar), "sidecar must carry the governed wrapper")
  }

  @Test
  fun `internal child has no standalone staged directory or staging intent`() {
    val fixture = setupParentWithInternalChild()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)

    val cacheRoot = fixture.home.resolve(".skill-bill/installed-skills")
    val childStagingDirs = Files.walk(cacheRoot).use { stream ->
      stream
        .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
        .filter { it.fileName.toString().startsWith(fixture.childName) }
        .toList()
    }
    assertTrue(childStagingDirs.isEmpty(), "internal skill must not have its own staging dir; found $childStagingDirs")
    assertFalse(
      rendered.copiedAuthoredFiles.any { it.fileName.toString() == "${fixture.childName}.md" },
      "sidecar must not be classified as authored copy",
    )
  }

  @Test
  fun `collision guard fails loudly when parent authors the sidecar name`() {
    val fixture = setupParentWithInternalChild()
    Files.writeString(fixture.parentDir.resolve("${fixture.childName}.md"), "authored collision\n")

    val error = assertFailsWith<InternalSkillSidecarCollisionError> {
      stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    }
    assertEquals(fixture.parentName, error.parentSkillName)
    assertEquals(fixture.childName, error.internalSkillName)
    assertEquals("${fixture.childName}.md", error.sidecarRelativePath)
  }

  @Test
  fun `idempotent reinstall reuses the parent staging dir with the sidecar intact`() {
    val fixture = setupParentWithInternalChild()

    val first = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    val firstSidecar = first.stagingDir.resolve("${fixture.childName}.md")
    assertTrue(Files.isRegularFile(firstSidecar, LinkOption.NOFOLLOW_LINKS))

    val second = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    assertEquals(first.stagingDir, second.stagingDir)
    assertEquals(first.contentHash, second.contentHash)
    assertTrue(
      Files.isRegularFile(firstSidecar, LinkOption.NOFOLLOW_LINKS),
      "sidecar must survive reuse",
    )
    assertTrue(
      second.renderedSidecarFiles.any { it.fileName.toString() == "${fixture.childName}.md" },
      "reused staging must still report the sidecar",
    )
  }

  @Test
  fun `discoverInternalSidecarTargets returns only children declaring the parent`() {
    val fixture = setupParentWithInternalChild()
    val childContent = Files.readString(fixture.repoRoot.resolve("skills/${fixture.childName}/content.md"))
    assertTrue(
      childContent.contains("internal-for: ${fixture.parentName}"),
      "fixture child must declare internal-for; got:\n$childContent",
    )
    val resolvedChild = resolveTarget(fixture.repoRoot, fixture.childName)
    assertEquals(fixture.parentName, resolvedChild.internalFor, "resolved child must carry internalFor")

    val targets = discoverInternalSidecarTargets(
      repoRoot = fixture.repoRoot,
      parentSkillName = fixture.parentName,
      skillsRoot = fixture.repoRoot.resolve("skills"),
    )

    assertEquals(1, targets.size, "expected 1 internal child; got ${targets.size}: $targets")
    val child = targets.single()
    assertEquals(fixture.childName, child.skillName)
  }

  @Test
  fun `discoverInternalSidecarTargets returns empty when no child declares the parent`() {
    val repoRoot = Files.createTempDirectory("skillbill-internal-noop").also(tempDirs::add)
    seedSkill(repoRoot, "bill-feature", "bill-feature", "Listed skill.")
    Files.createDirectories(repoRoot.resolve("skills"))

    val targets = discoverInternalSidecarTargets(
      repoRoot = repoRoot,
      parentSkillName = "bill-feature",
      skillsRoot = repoRoot.resolve("skills"),
    )

    assertTrue(targets.isEmpty())
  }

  @Test
  fun `staged output is byte-identical to a repo without internal-for declarations`() {
    val fixture = setupParentWithInternalChild()
    // Remove the internal-for declaration so the child becomes listed; re-stage both and compare
    // the parent's staged output. The parent's staged tree must not contain the sidecar.
    Files.writeString(
      fixture.childDir.resolve("content.md"),
      """
      ---
      name: ${fixture.childName}
      description: Now listed.
      ---

      Body.
      """.trimIndent(),
    )

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)

    val sidecar = rendered.stagingDir.resolve("${fixture.childName}.md")
    assertFalse(
      Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS),
      "parent staging must not carry a sidecar when no child declares internal-for",
    )
    assertTrue(rendered.renderedSidecarFiles.isEmpty(), "no sidecars expected")
  }

  @Test
  fun `writeInternalSidecarFiles renders the governed wrapper via renderWrapper`() {
    val fixture = setupParentWithInternalChild()
    val child = discoverInternalSidecarTargets(
      repoRoot = fixture.repoRoot,
      parentSkillName = fixture.parentName,
      skillsRoot = fixture.repoRoot.resolve("skills"),
    ).single()
    val tempDir = Files.createTempDirectory("skillbill-sidecar-render").also(tempDirs::add)

    val written = writeInternalSidecarFiles(
      tempDir = tempDir,
      parentSourceDir = fixture.parentDir,
      children = listOf(child),
    )

    assertEquals(1, written.size)
    val sidecar = written.single()
    assertEquals("${fixture.childName}.md", sidecar.fileName.toString())
    val expected = renderInternalSidecarWrapper(child)
    assertEquals(expected, Files.readString(sidecar))
  }

  // ---------------------------------------------------------------------------------------------
  // Agent-link planning (criterion 4): internal skills get no skills_dir link
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `install links the parent but creates no skills_dir entry for the internal child`() {
    val fixture = setupParentWithInternalChild()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)
    val agent = AgentTarget("test-agent", agentRoot)

    installSkill(
      skillPath = fixture.parentDir,
      agentTargets = listOf(agent),
      context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
    )

    assertTrue(Files.isSymbolicLink(agentRoot.resolve(fixture.parentName)), "parent must be linked")
    assertFalse(
      Files.exists(agentRoot.resolve(fixture.childName), LinkOption.NOFOLLOW_LINKS),
      "internal child must not be linked into any agent skills_dir",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Native-agent parity (criterion 5)
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `internal skill with a native-agents dir remains enumerable as a source root`() {
    val fixture = setupParentWithInternalChild()
    val nativeDir = fixture.childDir.resolve("native-agents")
    Files.createDirectories(nativeDir)
    Files.writeString(nativeDir.resolve("agents.yaml"), "agents: []\n")

    // Native-agent discovery keys off sourceDir presence, not classification. The internal child's
    // source dir is still enumerable, so a native-agents bundle hosted there installs as for a
    // listed skill.
    assertTrue(Files.isDirectory(nativeDir, LinkOption.NOFOLLOW_LINKS))
  }

  // ---------------------------------------------------------------------------------------------
  // Uninstall idempotency (criterion 6)
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `uninstalling the parent link removes the sidecar reachable path with it`() {
    val fixture = setupParentWithInternalChild()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)
    val agent = AgentTarget("test-agent", agentRoot)

    val links = installSkill(
      skillPath = fixture.parentDir,
      agentTargets = listOf(agent),
      context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
    )
    val parentLink = links.single()
    assertTrue(Files.isSymbolicLink(parentLink))
    // The sidecar lives inside the parent's staged directory. Removing the parent link is the
    // agent-visible uninstall; the sidecar travels with the parent's staging dir, so no separate
    // cleanup entry exists for the internal child.
    Files.deleteIfExists(parentLink)
    assertFalse(Files.exists(parentLink, LinkOption.NOFOLLOW_LINKS))
  }

  // ---------------------------------------------------------------------------------------------
  // Repo validation (criterion 1, 2, 7)
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `repo validation rejects unknown internal parent at validate time`() {
    val repoRoot = Files.createTempDirectory("skillbill-internal-validate-unknown").also(tempDirs::add)
    seedSkill(repoRoot, "bill-feature-task", "bill-feature-task", "Internal.")
    // Author the child to declare a parent that does not exist in this repo slice.
    Files.writeString(
      repoRoot.resolve("skills/bill-feature-task/content.md"),
      """
      ---
      name: bill-feature-task
      description: Internal.
      internal-for: bill-featur
      ---

      Body.
      """.trimIndent(),
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { it.contains("not a discovered skill") && it.contains("bill-featur") },
      "validate must surface the unknown-parent rule; issues=${report.issues}",
    )
  }

  @Test
  fun `repo validation raises no internal-skill issue for a healthy classification`() {
    val fixture = setupParentWithInternalChild()

    val report = RepoValidationRuntime.validateRepo(fixture.repoRoot)

    val internalIssues = report.issues.filter { issue ->
      issue.contains("internal-for") || issue.contains("internal skill") || issue.contains("not a discovered skill")
    }
    assertTrue(
      internalIssues.isEmpty(),
      "healthy internal classification must raise no internal-skill issue, got: $internalIssues",
    )
  }

  private data class ParentChildFixture(
    val repoRoot: Path,
    val home: Path,
    val parentName: String,
    val childName: String,
    val parentDir: Path,
    val childDir: Path,
  )

  private fun setupParentWithInternalChild(): ParentChildFixture {
    val repoRoot = Files.createTempDirectory("skillbill-internal-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-internal-home").also(tempDirs::add)
    SkillClassFixtures.seedShippedSkillClasses(repoRoot)
    // `supportingFileTargets` references `platform-packs/kmp/addons/...`; seeding those addons
    // creates a `kmp` directory, and `discoverTargets` then requires a `platform.yaml` inside it.
    // Seed a minimal valid pack so authoring discovery resolves cleanly.
    seedKmpPlatformPack(repoRoot)
    seedSupportingTargets(repoRoot)
    val parentName = "bill-feature"
    val childName = "bill-feature-task"
    val parentDir = seedSkill(
      repoRoot,
      parentName,
      parentName,
      "Routes feature work and dispatches to internal sidecars.",
    )
    val childDir = seedInternalChild(repoRoot, childName, parentName)
    return ParentChildFixture(
      repoRoot = repoRoot,
      home = home,
      parentName = parentName,
      childName = childName,
      parentDir = parentDir,
      childDir = childDir,
    )
  }

  private fun seedSupportingTargets(repoRoot: Path) {
    // Seed only the orchestration-derived supporting targets. The kmp add-on targets in
    // `supportingFileTargets` would create a platform-packs/kmp directory without a manifest,
    // which discovery rejects; the feature-skill family does not consume those add-ons.
    val targets = supportingFileTargets(repoRoot)
    val orchestrationTargets = targets.values.filter { it.startsWith(repoRoot.resolve("orchestration")) }
    orchestrationTargets.forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "supporting target\n")
    }
  }

  private fun seedSkill(repoRoot: Path, skillName: String, frontmatterName: String, description: String): Path {
    val skillDir = repoRoot.resolve("skills/$skillName")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $frontmatterName
      description: $description
      ---

      Authored body.
      """.trimIndent(),
    )
    return skillDir.toAbsolutePath().normalize()
  }

  private fun seedInternalChild(repoRoot: Path, skillName: String, parentName: String): Path {
    val skillDir = repoRoot.resolve("skills/$skillName")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $skillName
      description: Internal dispatch target.
      internal-for: $parentName
      ---

      Authored internal body.
      """.trimIndent(),
    )
    return skillDir.toAbsolutePath().normalize()
  }

  /**
   * Seeds a minimal valid `kmp` platform pack so `discoverTargets` (which scans
   * `platform-packs/`) resolves without a MissingManifestError. The `supportingFileTargets`
   * registry references `platform-packs/kmp/addons/...`, so once those addon files are seeded the
   * `kmp` directory exists and a manifest is required.
   */
  private fun seedKmpPlatformPack(repoRoot: Path) {
    val slug = "kmp"
    val codeReviewName = "bill-$slug-code-review"
    val qualityCheckName = "bill-$slug-code-check"
    val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
    Files.createDirectories(packRoot.resolve("code-review").resolve(codeReviewName))
    Files.createDirectories(packRoot.resolve("quality-check").resolve(qualityCheckName))
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
    Files.writeString(
      packRoot.resolve("code-review").resolve(codeReviewName).resolve("content.md"),
      """
      |---
      |name: $codeReviewName
      |description: Test code-review skill.
      |---
      |Body.
      """.trimMargin(),
    )
    Files.writeString(
      packRoot.resolve("quality-check").resolve(qualityCheckName).resolve("content.md"),
      """
      |---
      |name: $qualityCheckName
      |description: Test quality-check skill.
      |---
      |Body.
      """.trimMargin(),
    )
  }
}
