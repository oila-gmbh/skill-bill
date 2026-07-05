package skillbill.install

import skillbill.error.InternalSkillSidecarCollisionError
import skillbill.error.InvalidInternalSkillClassificationError
import skillbill.install.apply.nativeAgentSourceRoots
import skillbill.install.apply.standaloneInstallableSkills
import skillbill.install.model.AgentTarget
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.plan.InstallContext
import skillbill.install.plan.installSkill
import skillbill.install.plan.uninstallTargets
import skillbill.install.staging.discoverInternalSidecarTargets
import skillbill.install.staging.stageInstalledSkill
import skillbill.install.staging.writeInternalSidecarFiles
import skillbill.scaffold.authoring.renderWrapper
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * SKILL-102 (PD2/PD6): internal-skill install staging.
 *
 * Covers sidecar staging layout and naming, absence of standalone staging and skills_dir entries
 * for internal skills, the collision guard, native-agent source-root parity, install/uninstall
 * idempotency, the content-hash byte-identity pin (criterion 7), custom skills-root threading,
 * cache-reuse sidecar verification, the direct link-skill refusal, and repo validation of
 * classification and sidecar references. Fixtures are created inside the tests, not from repo
 * skills.
 */
@Suppress("LargeClass") // one cohesive staging-test surface spanning base + pack extension rules
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
    assertEquals(
      renderWrapper(childTarget),
      Files.readString(sidecar),
      "sidecar must carry the governed wrapper",
    )
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
  fun `cache reuse re-renders when an expected sidecar was externally deleted`() {
    val fixture = setupParentWithInternalChild()

    val first = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    val sidecar = first.stagingDir.resolve("${fixture.childName}.md")
    Files.delete(sidecar)

    val second = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    assertEquals(first.contentHash, second.contentHash)
    assertTrue(
      Files.isRegularFile(second.stagingDir.resolve("${fixture.childName}.md"), LinkOption.NOFOLLOW_LINKS),
      "a pruned sidecar must be re-rendered instead of reused broken",
    )
  }

  @Test
  fun `discoverInternalSidecarTargets excludes listed siblings and children of other parents`() {
    val fixture = setupParentWithInternalChild()
    seedSkill(fixture.repoRoot, "bill-listed-sibling", "bill-listed-sibling", "Listed sibling.")
    seedSkill(fixture.repoRoot, "bill-other", "bill-other", "Another listed parent.")
    seedInternalChild(fixture.repoRoot, "bill-other-child", "bill-other")

    val targets = discoverInternalSidecarTargets(
      repoRoot = fixture.repoRoot,
      parentSkillName = fixture.parentName,
      skillsRoot = fixture.repoRoot.resolve("skills"),
    )

    assertEquals(
      listOf(fixture.childName),
      targets.map { it.skillName },
      "only children declaring '${fixture.parentName}' may stage into its directory",
    )
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
    // Remove the internal-for declaration so the child becomes listed; re-stage and confirm the
    // parent's staged output carries no sidecar.
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
  fun `parent content hash ignores listed siblings and changes only for internal children`() {
    val (repoRoot, home) = setupRepoBase()
    val parentDir = seedSkill(repoRoot, "bill-feature", "bill-feature", "Routing parent.")

    val alone = stageInstalledSkill(repoRoot, parentDir, home)

    seedSkill(repoRoot, "bill-feature-task", "bill-feature-task", "Listed sibling.")
    val withListedSibling = stageInstalledSkill(repoRoot, parentDir, home)
    assertEquals(
      alone.contentHash,
      withListedSibling.contentHash,
      "a listed sibling must not change the parent's content hash (criterion 7 byte-identity)",
    )
    assertTrue(withListedSibling.renderedSidecarFiles.isEmpty())

    seedInternalChild(repoRoot, "bill-feature-task", "bill-feature")
    val withInternalChild = stageInstalledSkill(repoRoot, parentDir, home)
    assertNotEquals(
      alone.contentHash,
      withInternalChild.contentHash,
      "classifying the sibling internal must invalidate the parent's cache entry",
    )
    assertTrue(
      Files.isRegularFile(
        withInternalChild.stagingDir.resolve("bill-feature-task.md"),
        LinkOption.NOFOLLOW_LINKS,
      ),
    )
  }

  @Test
  fun `stageInstalledSkill honors an explicit skills root for internal-child discovery`() {
    val fixture = setupParentWithInternalChild()
    val defaultRoot = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    val explicitRoot = stageInstalledSkill(
      fixture.repoRoot,
      fixture.parentDir,
      fixture.home,
      skillsRoot = fixture.repoRoot.resolve("skills"),
    )
    assertEquals(defaultRoot.contentHash, explicitRoot.contentHash)

    val emptySkillsRoot = Files.createTempDirectory("skillbill-empty-skills").also(tempDirs::add)
    val withoutChildren = stageInstalledSkill(
      fixture.repoRoot,
      fixture.parentDir,
      fixture.home,
      skillsRoot = emptySkillsRoot,
    )
    assertNotEquals(
      defaultRoot.contentHash,
      withoutChildren.contentHash,
      "an explicit skills root must drive internal-child discovery instead of repoRoot/skills",
    )
    assertTrue(withoutChildren.renderedSidecarFiles.isEmpty())
  }

  @Test
  fun `writeInternalSidecarFiles writes the pre-rendered governed wrapper`() {
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

    val sidecar = written.single()
    assertEquals("${fixture.childName}.md", sidecar.fileName.toString())
    // Independent expectation: render the wrapper through the authoring seam directly.
    assertEquals(
      renderWrapper(resolveTarget(fixture.repoRoot, fixture.childName)),
      Files.readString(sidecar),
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Standalone-install and native-agent filters (criteria 4 and 5)
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `standaloneInstallableSkills excludes internal skills that nativeAgentSourceRoots retains`() {
    val parent = planSkill("bill-feature", internalFor = null)
    val child = planSkill("bill-feature-task", internalFor = "bill-feature")
    val packSkill = planSkill("bill-kotlin-code-review", internalFor = null, platformSlug = "kotlin")
    val unselectedPackSkill = planSkill("bill-ios-code-review", internalFor = null, platformSlug = "ios")
    val skills = listOf(parent, child, packSkill, unselectedPackSkill)

    val standalone = standaloneInstallableSkills(skills, selectedPlatformSlugs = setOf("kotlin"))
    assertEquals(
      listOf("bill-feature", "bill-kotlin-code-review"),
      standalone.map { it.name },
      "internal skills and unselected pack skills must not stage standalone or link into skills_dir",
    )

    val sourceRoots = nativeAgentSourceRoots(skills, selectedPlatformSlugs = setOf("kotlin"))
    assertTrue(
      child.sourceDir in sourceRoots,
      "an internal skill's dir must remain a native-agent source root (native-agent parity)",
    )
    assertFalse(unselectedPackSkill.sourceDir in sourceRoots)
  }

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

  @Test
  fun `installSkill refuses to link an internal skill directly`() {
    val fixture = setupParentWithInternalChild()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)

    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      installSkill(
        skillPath = fixture.childDir,
        agentTargets = listOf(AgentTarget("test-agent", agentRoot)),
        context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
      )
    }
    assertTrue(error.message.orEmpty().contains("internal-for: ${fixture.parentName}"))
    assertFalse(
      Files.exists(agentRoot.resolve(fixture.childName), LinkOption.NOFOLLOW_LINKS),
      "refused link must leave no skills_dir entry",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Uninstall idempotency (criterion 6)
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `uninstallTargets removes the parent link and repeats as a no-op`() {
    val fixture = setupParentWithInternalChild()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)

    val links = installSkill(
      skillPath = fixture.parentDir,
      agentTargets = listOf(AgentTarget("test-agent", agentRoot)),
      context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
    )
    val parentLink = links.single()
    assertTrue(Files.isSymbolicLink(parentLink))

    val removed = uninstallTargets(links)
    assertEquals(links, removed)
    assertFalse(Files.exists(parentLink, LinkOption.NOFOLLOW_LINKS))

    val removedAgain = uninstallTargets(links)
    assertTrue(removedAgain.isEmpty(), "repeat uninstall must be a no-op, got $removedAgain")
  }

  // ---------------------------------------------------------------------------------------------
  // Repo validation (classification, blank values, sidecar references)
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `repo validation rejects unknown internal parent at validate time`() {
    val repoRoot = Files.createTempDirectory("skillbill-internal-validate-unknown").also(tempDirs::add)
    seedSkill(repoRoot, "bill-feature-task", "bill-feature-task", "Internal.")
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
  fun `repo validation rejects a blank internal-for value read from content md`() {
    val repoRoot = Files.createTempDirectory("skillbill-internal-validate-blank").also(tempDirs::add)
    seedSkill(repoRoot, "bill-feature-task", "bill-feature-task", "Internal.")
    Files.writeString(
      repoRoot.resolve("skills/bill-feature-task/content.md"),
      """
      ---
      name: bill-feature-task
      description: Internal.
      internal-for:
      ---

      Body.
      """.trimIndent(),
    )

    val report = RepoValidationRuntime.validateRepo(repoRoot)

    assertFalse(report.passed)
    assertTrue(
      report.issues.any { it.contains("empty value") && it.contains("bill-feature-task") },
      "a blank internal-for must fail loudly, not degrade to listed; issues=${report.issues}",
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

  @Test
  fun `repo validation rejects a sidecar reference to another parent's internal child`() {
    val fixture = setupParentWithInternalChild()
    seedSkill(
      fixture.repoRoot,
      "bill-outsider",
      "bill-outsider",
      "Listed skill referencing a foreign sidecar.",
      body = "Read the file `${fixture.childName}.md` and execute it.",
    )

    val report = RepoValidationRuntime.validateRepo(fixture.repoRoot)

    assertTrue(
      report.issues.any { it.contains("bill-outsider") && it.contains("not co-located") },
      "a sidecar reference outside the parent's directory must fail validate; issues=${report.issues}",
    )
  }

  @Test
  fun `repo validation rejects a sidecar reference to a listed skill`() {
    val fixture = setupParentWithInternalChild()
    seedSkill(fixture.repoRoot, "bill-listed", "bill-listed", "Listed skill.")
    seedSkill(
      fixture.repoRoot,
      "bill-referrer",
      "bill-referrer",
      "Listed skill referencing a listed skill as a sidecar.",
      body = "Read the file `bill-listed.md` and execute it.",
    )

    val report = RepoValidationRuntime.validateRepo(fixture.repoRoot)

    assertTrue(
      report.issues.any { it.contains("bill-referrer") && it.contains("renders no sidecar") },
      "referencing a listed skill as a sidecar file must fail validate; issues=${report.issues}",
    )
  }

  @Test
  fun `repo validation accepts the parent referencing its own child sidecar`() {
    val fixture = setupParentWithInternalChild(
      parentBody = "Read the file `bill-feature-task.md` located in this skill's installed directory.",
    )

    val report = RepoValidationRuntime.validateRepo(fixture.repoRoot)

    val referenceIssues = report.issues.filter { it.contains("references sidecar") }
    assertTrue(
      referenceIssues.isEmpty(),
      "the parent's own-child sidecar reference is the supported dispatch contract, got: $referenceIssues",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // SKILL-104: pack-aware selection-shaped sidecar staging (PD2/PD3)
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `selected pack child stages as a sidecar inside the parent staged directory`() {
    val fixture = setupParentWithInternalPackChild()

    val rendered = stageInstalledSkill(
      fixture.repoRoot,
      fixture.parentDir,
      fixture.home,
      selectedPackSkills = listOf(fixture.packChildPlanSkill),
    )

    val sidecar = rendered.stagingDir.resolve("${fixture.packChildName}.md")
    assertTrue(Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS), "missing pack sidecar at $sidecar")
    assertTrue(sidecar in rendered.renderedSidecarFiles, "pack sidecar not reported in renderedSidecarFiles")
    val packChildTarget = resolveTarget(fixture.repoRoot, fixture.packChildName)
    assertEquals(
      renderWrapper(packChildTarget),
      Files.readString(sidecar),
      "pack sidecar must carry the same full governed wrapper a listed pack skill would render",
    )
  }

  @Test
  fun `unselected pack child contributes no sidecar and no hash contribution`() {
    val fixture = setupParentWithInternalPackChild()

    // No selectedPackSkills passed -> the pack child is unselected -> no sidecar.
    val unselected = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    assertTrue(unselected.renderedSidecarFiles.isEmpty(), "unselected pack must stage no sidecars")
    assertFalse(
      Files.exists(unselected.stagingDir.resolve("${fixture.packChildName}.md"), LinkOption.NOFOLLOW_LINKS),
      "unselected pack sidecar must not be written",
    )

    val selected = stageInstalledSkill(
      fixture.repoRoot,
      fixture.parentDir,
      fixture.home,
      selectedPackSkills = listOf(fixture.packChildPlanSkill),
    )
    assertNotEquals(
      unselected.contentHash,
      selected.contentHash,
      "selecting the pack must invalidate the parent's content hash (PD3 selection-aware hashing)",
    )
  }

  @Test
  fun `editing the pack child content invalidates the parent content hash`() {
    val fixture = setupParentWithInternalPackChild()

    val first = stageInstalledSkill(
      fixture.repoRoot,
      fixture.parentDir,
      fixture.home,
      selectedPackSkills = listOf(fixture.packChildPlanSkill),
    )

    Files.writeString(
      fixture.packChildContentFile,
      Files.readString(fixture.packChildContentFile) + "\n\n## Additional reviewed section.\n",
    )
    val afterEdit = stageInstalledSkill(
      fixture.repoRoot,
      fixture.parentDir,
      fixture.home,
      selectedPackSkills = listOf(fixture.packChildPlanSkill),
    )
    assertNotEquals(
      first.contentHash,
      afterEdit.contentHash,
      "editing the pack child's content.md must invalidate the parent hash",
    )
  }

  @Test
  fun `cache reuse re-renders an externally deleted pack sidecar`() {
    val fixture = setupParentWithInternalPackChild()

    val first = stageInstalledSkill(
      fixture.repoRoot,
      fixture.parentDir,
      fixture.home,
      selectedPackSkills = listOf(fixture.packChildPlanSkill),
    )
    val sidecar = first.stagingDir.resolve("${fixture.packChildName}.md")
    Files.delete(sidecar)

    val second = stageInstalledSkill(
      fixture.repoRoot,
      fixture.parentDir,
      fixture.home,
      selectedPackSkills = listOf(fixture.packChildPlanSkill),
    )
    assertEquals(first.contentHash, second.contentHash)
    assertTrue(
      Files.isRegularFile(second.stagingDir.resolve("${fixture.packChildName}.md"), LinkOption.NOFOLLOW_LINKS),
      "a pruned pack sidecar must be re-rendered instead of reused broken",
    )
  }

  @Test
  fun `pack-aware staging is byte-identical to pre-change when no skill opts in`() {
    // Inertness (criterion 5): with no opted-in repo skill, the parent stages identically whether
    // or not the pack-aware mechanism is present. Concretely: no sidecar, no hash contribution.
    val fixture = setupParentWithInternalPackChild()

    // No pack opt-in: the parent stages with no sidecars.
    val inert = stageInstalledSkill(fixture.repoRoot, fixture.parentDir, fixture.home)
    assertTrue(inert.renderedSidecarFiles.isEmpty(), "inert staging must carry no sidecars")

    // A second staging with an empty selectedPackSkills list must be byte-identical.
    val inertExplicitEmpty = stageInstalledSkill(
      fixture.repoRoot,
      fixture.parentDir,
      fixture.home,
      selectedPackSkills = emptyList(),
    )
    assertEquals(
      inert.contentHash,
      inertExplicitEmpty.contentHash,
      "an explicit empty pack-skill list must not change the hash",
    )
    assertEquals(inert.stagingDir, inertExplicitEmpty.stagingDir)
  }

  @Test
  fun `standaloneInstallableSkills excludes internal pack skills that nativeAgentSourceRoots retains`() {
    val parent = planSkill("bill-code-review", internalFor = null)
    val packInternal = planSkill(
      "bill-kotlin-code-review",
      internalFor = "bill-code-review",
      platformSlug = "kotlin",
    )
    val skills = listOf(parent, packInternal)

    val standalone = standaloneInstallableSkills(skills, selectedPlatformSlugs = setOf("kotlin"))
    assertEquals(
      listOf("bill-code-review"),
      standalone.map { it.name },
      "an internal pack skill must not stage standalone or link into skills_dir",
    )

    // PD6 verify-only: native-agent source roots keep enumerating the internal pack skill.
    val sourceRoots = nativeAgentSourceRoots(skills, selectedPlatformSlugs = setOf("kotlin"))
    assertTrue(
      packInternal.sourceDir in sourceRoots,
      "an internal pack skill's dir must remain a native-agent source root (PD6 parity)",
    )
  }

  @Test
  fun `installSkill refuses to link an internal pack skill directly`() {
    val fixture = setupParentWithInternalPackChild()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)

    val error = assertFailsWith<InvalidInternalSkillClassificationError> {
      installSkill(
        skillPath = fixture.packChildDir,
        agentTargets = listOf(AgentTarget("test-agent", agentRoot)),
        context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
      )
    }
    assertTrue(error.message.orEmpty().contains("internal-for: ${fixture.parentName}"))
    assertFalse(
      Files.exists(agentRoot.resolve(fixture.packChildName), LinkOption.NOFOLLOW_LINKS),
      "refused pack link must leave no skills_dir entry",
    )
  }

  @Test
  fun `repo validation accepts a healthy pack internal child classification`() {
    val fixture = setupParentWithInternalPackChild()

    val report = RepoValidationRuntime.validateRepo(fixture.repoRoot)

    val internalIssues = report.issues.filter { issue ->
      issue.contains("internal-for") || issue.contains("internal skill") || issue.contains("platform-pack skill")
    }
    assertTrue(
      internalIssues.isEmpty(),
      "healthy pack internal classification must raise no internal-skill issue, got: $internalIssues",
    )
  }

  @Test
  fun `repo validation rejects a pack child declaring an unknown parent`() {
    val fixture = setupParentWithInternalPackChild()
    Files.writeString(
      fixture.packChildContentFile,
      """
      ---
      name: ${fixture.packChildName}
      description: Internal pack.
      internal-for: bill-no-such-parent
      ---

      Body.
      """.trimIndent(),
    )

    val report = RepoValidationRuntime.validateRepo(fixture.repoRoot)

    assertTrue(
      report.issues.any { it.contains("not a discovered skill") && it.contains(fixture.packChildName) },
      "validate must surface the unknown-parent rule for a pack skill; issues=${report.issues}",
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

  private data class ParentWithInternalPackChildFixture(
    val repoRoot: Path,
    val home: Path,
    val parentName: String,
    val parentDir: Path,
    val packChildName: String,
    val packChildDir: Path,
    val packChildContentFile: Path,
    val packChildPlanSkill: InstallPlanSkill,
  )

  private fun setupRepoBase(): Pair<Path, Path> {
    val repoRoot = Files.createTempDirectory("skillbill-internal-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-internal-home").also(tempDirs::add)
    SkillClassFixtures.seedShippedSkillClasses(repoRoot)
    seedSkill(
      repoRoot,
      "bill-code-check",
      "bill-code-check",
      "Routes quality checks and dispatches to pack sidecars.",
    )
    // `supportingFileTargets` references `platform-packs/kmp/addons/...`; seeding those addons
    // creates a `kmp` directory, and `discoverTargets` then requires a `platform.yaml` inside it.
    // Seed a minimal valid pack so authoring discovery resolves cleanly.
    seedKmpPlatformPack(repoRoot)
    seedSupportingTargets(repoRoot)
    return repoRoot to home
  }

  private fun setupParentWithInternalChild(parentBody: String = "Authored body."): ParentChildFixture {
    val (repoRoot, home) = setupRepoBase()
    val parentName = "bill-feature"
    val childName = "bill-feature-task"
    val parentDir = seedSkill(
      repoRoot,
      parentName,
      parentName,
      "Routes feature work and dispatches to internal sidecars.",
      body = parentBody,
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

  private fun planSkill(name: String, internalFor: String?, platformSlug: String? = null): InstallPlanSkill =
    InstallPlanSkill(
      name = name,
      sourceDir = Path.of("/repo/skills/$name").toAbsolutePath().normalize(),
      kind = if (platformSlug == null) InstallPlanSkillKind.BASE else InstallPlanSkillKind.PLATFORM_PACK,
      platformSlug = platformSlug,
      internalFor = internalFor,
    )

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

  private fun seedSkill(
    repoRoot: Path,
    skillName: String,
    frontmatterName: String,
    description: String,
    body: String = "Authored body.",
  ): Path {
    val skillDir = repoRoot.resolve("skills/$skillName")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
      ---
      name: $frontmatterName
      description: $description
      ---

      $body
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
   * SKILL-104: seeds a base-skill parent (`bill-code-review`) plus a minimal `kotlin` platform pack
   * whose code-review baseline skill (`bill-kotlin-code-review`) declares
   * `internal-for: bill-code-review`. The pack child is opted-in, but the parent's directory does
   * not carry the sidecar unless the pack is in the selectedPackSkills list.
   */
  private fun setupParentWithInternalPackChild(): ParentWithInternalPackChildFixture {
    val (repoRoot, home) = setupRepoBase()
    val parentName = "bill-code-review"
    val parentDir = seedSkill(
      repoRoot,
      parentName,
      parentName,
      "Routes code review and dispatches to pack sidecars.",
    )

    val slug = "kotlin"
    val packChildName = "bill-$slug-code-review"
    val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
    val packChildDir = packRoot.resolve("code-review").resolve(packChildName)
    Files.createDirectories(packChildDir)
    val packChildContentFile = packChildDir.resolve("content.md")
    Files.writeString(
      packChildContentFile,
      """
      |---
      |name: $packChildName
      |description: Internal pack dispatch target.
      |internal-for: $parentName
      |---
      |
      |Authored internal pack body.
      """.trimMargin(),
    )
    seedKotlinPlatformPackWithBaseline(repoRoot, slug, packChildName)

    val packChildPlanSkill = InstallPlanSkill(
      name = packChildName,
      sourceDir = packChildDir.toAbsolutePath().normalize(),
      kind = InstallPlanSkillKind.PLATFORM_PACK,
      platformSlug = slug,
      internalFor = parentName,
    )
    return ParentWithInternalPackChildFixture(
      repoRoot = repoRoot,
      home = home,
      parentName = parentName,
      parentDir = parentDir.toAbsolutePath().normalize(),
      packChildName = packChildName,
      packChildDir = packChildDir.toAbsolutePath().normalize(),
      packChildContentFile = packChildContentFile.toAbsolutePath().normalize(),
      packChildPlanSkill = packChildPlanSkill,
    )
  }

  private fun seedKotlinPlatformPackWithBaseline(repoRoot: Path, slug: String, codeReviewName: String) {
    val packRoot = repoRoot.resolve("platform-packs").resolve(slug)
    Files.createDirectories(packRoot)
    val qualityCheckName = "bill-$slug-code-check"
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
      packRoot.resolve("quality-check").resolve(qualityCheckName).resolve("content.md"),
      """
      |---
      |name: $qualityCheckName
      |description: Test quality-check skill.
      |internal-for: bill-code-check
      |---
      |Body.
      """.trimMargin(),
    )
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
      |internal-for: bill-code-check
      |---
      |Body.
      """.trimMargin(),
    )
  }
}
