package skillbill.install

import skillbill.error.SkillBillRuntimeException
import skillbill.install.model.AgentTarget
import skillbill.install.model.RenderedSkill
import skillbill.scaffold.renderPointer
import skillbill.scaffold.renderWrapper
import skillbill.scaffold.requiredSupportingFilesForSkill
import skillbill.scaffold.resolveTarget
import skillbill.scaffold.supportingFileTargets
import skillbill.testsupport.SkillClassFixtures
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-40 subtask 2: install staging pipeline.
 *
 * These tests assert that `installSkill`/`stageInstalledSkill` materialize a per-skill staging
 * directory under `~/.skill-bill/installed-skills/<slug>-<hash>/` outside the repo, copy authored
 * material verbatim, render `SKILL.md` and pointer files, and never write back into the source
 * tree. Failure paths (malformed frontmatter, missing pointer target) must fail closed without
 * leaving a partial staging dir.
 */
class InstallStagingTest {
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
  fun `authored files are copied verbatim into the staging dir`() {
    val fixture = setupFixture()
    val skillSnapshot = snapshotTree(fixture.skillDir)

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    assertTrue(rendered.copiedAuthoredFiles.isNotEmpty(), "expected authored files to be copied")
    skillSnapshot.forEach { (rel, entry) ->
      if (rel == "SKILL.md") {
        return@forEach
      }
      if (entry !is TreeEntry.RegularFile) {
        return@forEach
      }
      val staged = rendered.stagingDir.resolve(rel)
      assertTrue(Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS), "missing staged authored file: $rel")
      assertContentEquals(entry.bytes, Files.readAllBytes(staged), "byte mismatch for staged authored file $rel")
    }
  }

  @Test
  fun `staging dir contains a freshly rendered SKILL_md`() {
    val fixture = setupFixture()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    val target = resolveTarget(fixture.repoRoot, fixture.skillName)
    val expectedSkillBytes = renderWrapper(target).toByteArray(StandardCharsets.UTF_8)
    assertContentEquals(expectedSkillBytes, Files.readAllBytes(rendered.renderedSkillFile))
  }

  @Test
  fun `pointer files are materialized in staging via renderPointer`() {
    val fixture = setupFixture()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    fixture.pointerSpecs.forEach { (manifest, spec) ->
      val staged = rendered.stagingDir.resolve(spec.name)
      assertTrue(Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS), "missing pointer file ${spec.name}")
      val expected = renderPointer(repoRoot = fixture.repoRoot, packRoot = manifest.packRoot, spec = spec)
      assertEquals(expected, String(Files.readAllBytes(staged), StandardCharsets.UTF_8))
    }
  }

  @Test
  fun `generated supporting pointers for skills are materialized in staging`() {
    val repoRoot = Files.createTempDirectory("skillbill-install-staging-skill-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-staging-skill-home").also(tempDirs::add)
    val skillDir = repoRoot.resolve("skills/bill-code-review")
    Files.createDirectories(skillDir)
    seedTopLevelSkillContent(skillDir)
    SkillClassFixtures.seedShippedSkillClasses(repoRoot)
    val targets = supportingFileTargets(repoRoot)
    requiredSupportingFilesForSkill("bill-code-review", repoRoot).map(targets::getValue).forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "supporting target\n")
    }

    val rendered = stageInstalledSkill(repoRoot, skillDir, home)

    requiredSupportingFilesForSkill("bill-code-review", repoRoot).forEach { fileName ->
      val sourceSidecar = skillDir.resolve(fileName)
      assertFalse(Files.exists(sourceSidecar, LinkOption.NOFOLLOW_LINKS), "source must not contain $fileName")
      val staged = rendered.stagingDir.resolve(fileName)
      assertTrue(Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS), "missing staged $fileName")
      val target = supportingFileTargets(repoRoot).getValue(fileName)
      val expected = Files.readString(target).trimEnd() + "\n"
      assertEquals(expected, Files.readString(staged), "support sidecar must inline canonical content for $fileName")
      assertFalse(Files.readString(staged).contains("../"), "inlined sidecar must not carry a dangling relative path")
    }
  }

  @Test
  fun `stale source generated artifacts are excluded from authored copy and replaced in staging`() {
    val fixture = setupFixture()
    val staleSkill = fixture.skillDir.resolve("SKILL.md")
    val stalePointer = fixture.skillDir.resolve(fixture.pointerNames.single())
    Files.writeString(staleSkill, "stale wrapper from source\n")
    Files.writeString(stalePointer, "stale pointer from source\n")
    val sourceBefore = snapshotTree(fixture.repoRoot)

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    val reused = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    val target = resolveTarget(fixture.repoRoot, fixture.skillName)
    val expectedSkillBytes = renderWrapper(target).toByteArray(StandardCharsets.UTF_8)
    assertContentEquals(expectedSkillBytes, Files.readAllBytes(rendered.renderedSkillFile))
    assertContentEquals(expectedSkillBytes, Files.readAllBytes(reused.renderedSkillFile))
    fixture.pointerSpecs.forEach { (manifest, spec) ->
      val staged = rendered.stagingDir.resolve(spec.name)
      val reusedStaged = reused.stagingDir.resolve(spec.name)
      val expected = renderPointer(repoRoot = fixture.repoRoot, packRoot = manifest.packRoot, spec = spec)
      assertEquals(expected, String(Files.readAllBytes(staged), StandardCharsets.UTF_8))
      assertEquals(expected, String(Files.readAllBytes(reusedStaged), StandardCharsets.UTF_8))
    }
    assertGeneratedArtifactsExcludedFromAuthoredCopy(rendered, fixture)
    assertGeneratedArtifactsExcludedFromAuthoredCopy(reused, fixture)
    val renderedPointers = reused.renderedPointerFiles
      .map { path -> reused.stagingDir.relativize(path).toString().replace(java.io.File.separatorChar, '/') }
      .toSet()
    fixture.pointerNames.forEach { pointerName ->
      assertTrue(pointerName in renderedPointers, "cache-hit result must classify $pointerName as rendered pointer")
    }
    assertEquals(sourceBefore, snapshotTree(fixture.repoRoot), "install staging must not mutate source tree")
  }

  @Test
  fun `source tree is byte identical before and after install`() {
    val fixture = setupFixture()
    val before = snapshotTree(fixture.repoRoot)

    stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    val after = snapshotTree(fixture.repoRoot)
    assertEquals(before.keys, after.keys, "file set under repoRoot changed after install")
    before.forEach { (rel, entry) ->
      assertEquals(entry, after[rel], "source tree mutated at $rel during install")
    }
  }

  @Test
  fun `installed symlink target equals the staging dir`() {
    val fixture = setupFixture()
    val agentRoot = fixture.home.resolve("agents")
    Files.createDirectories(agentRoot)
    val agent = AgentTarget("test-agent", agentRoot)
    // Compute the expected staging dir from production helpers BEFORE calling installSkill so the
    // oracle is independent of the SUT (no second stageInstalledSkill call after the install).
    val pointers = applicablePointers(fixture.repoRoot, fixture.skillDir)
    val authored = authoredFilesFor(fixture.skillDir, pointers)
    val expectedHash = computeInstallContentHash(fixture.skillDir, authored, pointers)
    val expectedTarget = installedSkillStagingDir(fixture.home, fixture.skillDir, expectedHash)

    val created = installSkill(
      skillPath = fixture.skillDir,
      agentTargets = listOf(agent),
      context = InstallContext(repoRoot = fixture.repoRoot, home = fixture.home),
    )

    assertEquals(1, created.size)
    val link = created.single()
    assertTrue(Files.isSymbolicLink(link))
    val readTarget = Files.readSymbolicLink(link)
    assertEquals(expectedTarget.toRealPath(), readTarget.toRealPath())
  }

  @Test
  fun `install fails closed when content_md frontmatter is malformed`() {
    val fixture = setupFixture()
    Files.writeString(fixture.skillDir.resolve("content.md"), "no frontmatter here\n")
    val cacheRoot = installedSkillsCacheRoot(fixture.home)
    val priorChildren = listCacheChildren(cacheRoot)

    assertFailsWith<SkillBillRuntimeException> {
      stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    }
    val afterChildren = listCacheChildren(cacheRoot)
    val newChildren = afterChildren - priorChildren
    assertTrue(
      newChildren.isEmpty(),
      "expected no leftover dirs after failure but found: $newChildren",
    )
  }

  @Test
  fun `install fails closed when a declared pointer target is missing`() {
    val fixture = setupFixture()
    // Remove a pointer target file so renderPointer throws IllegalArgumentException.
    val firstSpec = fixture.pointerSpecs.first().second
    Files.delete(fixture.repoRoot.resolve(firstSpec.target))
    val cacheRoot = installedSkillsCacheRoot(fixture.home)
    val priorChildren = listCacheChildren(cacheRoot)

    assertFailsWith<IllegalArgumentException> {
      stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    }
    val newChildren = listCacheChildren(cacheRoot) - priorChildren
    assertTrue(
      newChildren.isEmpty(),
      "expected cache root to be untouched on failure but found: $newChildren",
    )
  }

  @Test
  fun `idempotent reinstall reuses the existing staging dir via the content hash marker`() {
    val fixture = setupFixture()

    val first = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    val cacheRoot = installedSkillsCacheRoot(fixture.home)
    // Drop a sentinel into the staging dir AFTER the first install. If the second install reuses
    // the dir verbatim, the sentinel survives. If the staging dir is rebuilt the sentinel is gone.
    val sentinel = first.stagingDir.resolve("sentinel.txt")
    Files.writeString(sentinel, "x")

    val second = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    assertEquals(first.stagingDir, second.stagingDir)
    assertEquals(first.contentHash, second.contentHash)
    assertTrue(
      Files.isRegularFile(sentinel, LinkOption.NOFOLLOW_LINKS),
      "expected sentinel.txt to survive reuse but staging dir was rebuilt",
    )
    val staleTemps = listCacheChildren(cacheRoot).filter { it.startsWith(".staging-tmp-") }
    assertTrue(staleTemps.isEmpty(), "no leftover .staging-tmp- dirs expected, got: $staleTemps")
  }

  @Test
  fun `prune does not delete unrelated skills whose slug shares the current slug as a prefix`() {
    // F-016 regression: slugs are not delimiter-bounded — `bill-sample-code-review` is a prefix
    // of `bill-sample-code-review-security`. The prune predicate must NOT match unrelated skills.
    val fixture = setupFixture()
    // First install: produces ~/.skill-bill/installed-skills/<slug>-<hashA>/
    val first = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)
    val cacheRoot = installedSkillsCacheRoot(fixture.home)
    // Plant a sibling cache dir for an unrelated, longer-named skill that happens to share the
    // current slug as a prefix. Its leaf must still match the strict `<slug>-<16-hex>` shape so
    // the regression case (its name starting with `<currentSlug>-`) is exercised correctly.
    val firstSlug = first.stagingDir.fileName.toString().substringBeforeLast('-')
    val unrelatedLeaf = "$firstSlug-security-0123456789abcdef"
    val unrelatedDir = cacheRoot.resolve(unrelatedLeaf)
    Files.createDirectories(unrelatedDir)
    Files.writeString(unrelatedDir.resolve("marker.txt"), "keep me")
    // Mutate the source so the next install yields a different content hash and triggers prune.
    Files.writeString(fixture.skillDir.resolve("notes.md"), "verbatim notes v2\n")

    val second = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    assertTrue(
      Files.isDirectory(unrelatedDir, LinkOption.NOFOLLOW_LINKS),
      "prune deleted an unrelated skill's staging dir at $unrelatedDir",
    )
    assertTrue(
      Files.isRegularFile(unrelatedDir.resolve("marker.txt"), LinkOption.NOFOLLOW_LINKS),
      "prune mutated an unrelated skill's staging dir contents",
    )
    // Sanity: the prior same-slug staging dir (different hash) IS pruned.
    assertFalse(
      Files.isDirectory(first.stagingDir, LinkOption.NOFOLLOW_LINKS),
      "expected prior same-slug staging dir ${first.stagingDir} to be pruned by ${second.stagingDir}",
    )
  }

  @Test
  fun `content hash changes when support pointer target bytes change and stabilises without mutation`() {
    val repoRoot = Files.createTempDirectory("skillbill-hash-invalidation-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-hash-invalidation-home").also(tempDirs::add)
    val skillDir = repoRoot.resolve("skills/bill-code-review")
    Files.createDirectories(skillDir)
    seedTopLevelSkillContent(skillDir)
    SkillClassFixtures.seedShippedSkillClasses(repoRoot)
    val targets = supportingFileTargets(repoRoot)
    requiredSupportingFilesForSkill("bill-code-review", repoRoot).map(targets::getValue).forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "original content\n")
    }
    val pointers = applicablePointers(repoRoot, skillDir)
    val supportPointers = generatedSupportPointersFor(repoRoot, skillDir, "bill-code-review")
    val authored = authoredFilesFor(skillDir, pointers, supportPointers)

    val hashBefore = computeInstallContentHash(skillDir, authored, pointers, supportPointers)
    val firstTarget = supportPointers.first().target
    Files.writeString(firstTarget, "mutated content\n")
    val hashAfterMutation = computeInstallContentHash(skillDir, authored, pointers, supportPointers)
    Files.writeString(firstTarget, "original content\n")
    val hashAfterRestore = computeInstallContentHash(skillDir, authored, pointers, supportPointers)

    assertTrue(hashBefore != hashAfterMutation, "hash must change when support pointer target bytes change")
    assertEquals(hashBefore, hashAfterRestore, "hash must stabilise after restoring target bytes")
  }

  @Test
  fun `cache root is under tempHome dot skill-bill installed-skills outside the repo`() {
    val fixture = setupFixture()

    val rendered = stageInstalledSkill(fixture.repoRoot, fixture.skillDir, fixture.home)

    val expectedRoot = fixture.home.resolve(".skill-bill/installed-skills").toAbsolutePath().normalize()
    assertTrue(
      rendered.stagingDir.toAbsolutePath().normalize().startsWith(expectedRoot),
      "staging dir ${rendered.stagingDir} not under expected cache root $expectedRoot",
    )
    assertEquals(expectedRoot, installedSkillsCacheRoot(fixture.home))
    assertTrue(
      rendered.renderedSkillFile.toAbsolutePath().normalize().startsWith(rendered.stagingDir),
      "rendered SKILL.md ${rendered.renderedSkillFile} was not written under ${rendered.stagingDir}",
    )
    assertTrue(
      rendered.renderedPointerFiles.all { pointer ->
        pointer.toAbsolutePath().normalize().startsWith(rendered.stagingDir)
      },
      "rendered pointer files must stay inside the install staging dir",
    )
    assertFalse(
      rendered.stagingDir.toAbsolutePath().normalize().startsWith(fixture.repoRoot.toAbsolutePath().normalize()),
      "staging dir ${rendered.stagingDir} unexpectedly inside repo ${fixture.repoRoot}",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // Fixture builder
  // ---------------------------------------------------------------------------------------------

  private fun setupFixture(): Fixture {
    val repoRoot = Files.createTempDirectory("skillbill-install-staging-repo").also(tempDirs::add)
    val home = Files.createTempDirectory("skillbill-install-staging-home").also(tempDirs::add)
    val skillRelativeDir = "code-review/bill-sample-code-review"
    val packRoot = repoRoot.resolve("platform-packs/sample")
    val skillDir = packRoot.resolve(skillRelativeDir)
    seedSamplePack(repoRoot, packRoot, skillDir, skillRelativeDir)
    seedSampleSkillContent(skillDir)
    val pointers = applicablePointers(repoRoot, skillDir)
    return Fixture(
      repoRoot = repoRoot,
      home = home,
      skillDir = skillDir,
      skillName = skillDir.fileName.toString(),
      pointerSpecs = pointers,
      pointerNames = pointers.map { it.second.name }.toSet(),
    )
  }

  private fun seedSamplePack(repoRoot: Path, packRoot: Path, skillDir: Path, skillRelativeDir: String) {
    Files.createDirectories(skillDir)
    Files.createDirectories(repoRoot.resolve("orchestration/review-orchestrator"))
    Files.writeString(
      repoRoot.resolve("orchestration/review-orchestrator/PLAYBOOK.md"),
      "# Review orchestrator\n",
    )
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
      |platform: "sample"
      |contract_version: "1.1"
      |display_name: "Sample"
      |
      |routing_signals:
      |  strong:
      |    - ".sample"
      |  tie_breakers: []
      |
      |declared_code_review_areas: []
      |
      |declared_files:
      |  baseline: "$skillRelativeDir/content.md"
      |  areas: {}
      |area_metadata: {}
      |
      |pointers:
      |  $skillRelativeDir:
      |    - name: review-orchestrator.md
      |      target: orchestration/review-orchestrator/PLAYBOOK.md
      """.trimMargin(),
    )
  }

  private fun seedSampleSkillContent(skillDir: Path) {
    val frontmatter = """
      |---
      |name: bill-sample-code-review
      |description: Sample skill for install staging tests.
      |---
    """.trimMargin() + "\n\nAuthored body.\n"
    Files.writeString(skillDir.resolve("content.md"), frontmatter)
    // A sibling authored file (e.g. a sidecar) that should be copied verbatim.
    Files.writeString(skillDir.resolve("notes.md"), "verbatim notes\n")
  }

  private fun seedTopLevelSkillContent(skillDir: Path) {
    val frontmatter = """
      |---
      |name: bill-code-review
      |description: Review code.
      |---
    """.trimMargin() + "\n\nAuthored review guidance.\n"
    Files.writeString(skillDir.resolve("content.md"), frontmatter)
  }

  private fun assertGeneratedArtifactsExcludedFromAuthoredCopy(rendered: RenderedSkill, fixture: Fixture) {
    val copiedRelative = rendered.copiedAuthoredFiles
      .map { path -> rendered.stagingDir.relativize(path).toString().replace(java.io.File.separatorChar, '/') }
      .toSet()
    assertFalse("SKILL.md" in copiedRelative, "stale source SKILL.md must not be part of authored copy set")
    fixture.pointerNames.forEach { pointerName ->
      assertFalse(pointerName in copiedRelative, "stale source pointer $pointerName must not be authored copy")
    }
  }

  /**
   * Snapshot every entry under [root] with kind + content so symlinks, empty files, and dirs are
   * all surfaced in equality checks. F-004 (review): byte-only snapshots silently drop these and
   * would let regressions slip through.
   */
  private fun snapshotTree(root: Path): Map<String, TreeEntry> = Files.walk(root).use { stream ->
    stream
      .sorted()
      .filter { it != root }
      .toList()
      .associate { path ->
        val key = root.relativize(path).toString().replace(java.io.File.separatorChar, '/')
        val entry: TreeEntry = when {
          Files.isSymbolicLink(path) -> TreeEntry.Symlink(Files.readSymbolicLink(path).toString())
          Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> TreeEntry.Directory
          Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> TreeEntry.RegularFile(Files.readAllBytes(path))
          else -> TreeEntry.Other
        }
        key to entry
      }
  }

  private sealed class TreeEntry {
    data object Directory : TreeEntry()
    data object Other : TreeEntry()
    data class Symlink(val target: String) : TreeEntry()
    class RegularFile(val bytes: ByteArray) : TreeEntry() {
      override fun equals(other: Any?): Boolean = other is RegularFile && bytes.contentEquals(other.bytes)
      override fun hashCode(): Int = bytes.contentHashCode()
    }
  }

  private fun listCacheChildren(cacheRoot: Path): Set<String> {
    if (!Files.isDirectory(cacheRoot)) {
      return emptySet()
    }
    return Files.list(cacheRoot).use { stream ->
      stream.map { it.fileName.toString() }.toList().toSet()
    }
  }

  private data class Fixture(
    val repoRoot: Path,
    val home: Path,
    val skillDir: Path,
    val skillName: String,
    val pointerSpecs: List<Pair<skillbill.scaffold.model.PlatformManifest, skillbill.scaffold.model.PointerSpec>>,
    val pointerNames: Set<String>,
  )
}
