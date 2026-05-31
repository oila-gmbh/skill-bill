package skillbill.skillremove

import skillbill.domain.skillremove.model.ManifestEditKind
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalTarget
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the on-disk cascade discovery the desktop dialog and CLI consume.
 *
 * The fake [skillbill.domain.skillremove.SkillRemoveFileSystem] used in the runtime-domain test
 * suite scripts the port responses, which let the original implementation of
 * [SkillRemoveJvmFileSystem.discoverCascadedSkillNames] ship with a slug-prefix bug undetected:
 * for a horizontal skill named `bill-code-review`, the implementation built the search prefix
 * `bill-<platform>-bill-code-review` instead of `bill-<platform>-code-review`, so the cascade
 * never picked up `bill-kotlin-code-review`, `bill-kmp-code-review`, or their area specialists.
 * These tests pin the correct slug behavior using a real on-disk fixture.
 */
class SkillRemoveJvmFileSystemTest {
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
  fun `discoverCascadedSkillNames strips the bill prefix from the horizontal slug`() {
    val repoRoot = seedRepo()
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val request = SkillRemovalRequest(
      // SKILL-49: `bill-code-review` is a horizontal product skill; cascade-removal tests
      // exercise the maintainer path (`--allow-shipped`). The desktop UI never offers this.
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-review", allowShipped = true),
      repoRootAbsolutePath = repoRoot.toString(),
    )

    val discovered = fs.discoverCascadedSkillNames(request).toSet()

    assertEquals(
      setOf(
        "bill-kotlin-code-review",
        "bill-kotlin-code-review-architecture",
        "bill-kmp-code-review",
        "bill-kmp-code-review-ui",
      ),
      discovered,
    )
  }

  @Test
  fun `discoverCascadedSkillNames finds quality-check overrides as well as code-review overrides`() {
    val repoRoot = seedRepo()
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-check", allowShipped = true),
      repoRootAbsolutePath = repoRoot.toString(),
    )

    val discovered = fs.discoverCascadedSkillNames(request).toSet()

    assertEquals(setOf("bill-kotlin-code-check"), discovered)
  }

  @Test
  fun `planManifestEdits emits one REMOVE_CODE_REVIEW_AREA per discovered specialist`() {
    val repoRoot = seedRepo()
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val request = SkillRemovalRequest(
      // SKILL-49: `bill-code-review` is a horizontal product skill; cascade-removal tests
      // exercise the maintainer path (`--allow-shipped`). The desktop UI never offers this.
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-review", allowShipped = true),
      repoRootAbsolutePath = repoRoot.toString(),
    )
    val cascaded = fs.discoverCascadedSkillNames(request)

    val edits = fs.planManifestEdits(request, listOf("bill-code-review") + cascaded)

    val byManifest = edits.groupBy { it.manifestPath }
    val kotlinAreas = byManifest["platform-packs/kotlin/platform.yaml"]
      ?.filter { it.editKind == ManifestEditKind.REMOVE_CODE_REVIEW_AREA }
      ?.map { it.detail }
      ?.toSet()
      .orEmpty()
    val kmpAreas = byManifest["platform-packs/kmp/platform.yaml"]
      ?.filter { it.editKind == ManifestEditKind.REMOVE_CODE_REVIEW_AREA }
      ?.map { it.detail }
      ?.toSet()
      .orEmpty()
    assertEquals(setOf("architecture"), kotlinAreas)
    assertEquals(setOf("ui"), kmpAreas)
  }

  @Test
  fun `planManifestEdits emits REMOVE_DECLARED_FILES_BASELINE for each pack with a baseline override`() {
    val repoRoot = seedRepo()
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val request = SkillRemovalRequest(
      // SKILL-49: `bill-code-review` is a horizontal product skill; cascade-removal tests
      // exercise the maintainer path (`--allow-shipped`). The desktop UI never offers this.
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-review", allowShipped = true),
      repoRootAbsolutePath = repoRoot.toString(),
    )

    val edits = fs.planManifestEdits(request, emptyList())

    val baselineManifests = edits
      .filter { it.editKind == ManifestEditKind.REMOVE_DECLARED_FILES_BASELINE }
      .map { it.manifestPath }
      .toSet()
    assertEquals(
      setOf("platform-packs/kotlin/platform.yaml", "platform-packs/kmp/platform.yaml"),
      baselineManifests,
    )
  }

  @Test
  fun `planManifestEdits emits REMOVE_POINTERS_BLOCK_KEY for every removed code-review skill dir`() {
    val repoRoot = seedRepo()
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val request = SkillRemovalRequest(
      // SKILL-49: `bill-code-review` is a horizontal product skill; cascade-removal tests
      // exercise the maintainer path (`--allow-shipped`). The desktop UI never offers this.
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-review", allowShipped = true),
      repoRootAbsolutePath = repoRoot.toString(),
    )

    val edits = fs.planManifestEdits(request, emptyList())

    val pointerKeysByPack = edits
      .filter { it.editKind == ManifestEditKind.REMOVE_POINTERS_BLOCK_KEY }
      .groupBy({ it.manifestPath }, { it.detail })
      .mapValues { it.value.toSet() }
    assertEquals(
      setOf(
        "code-review/bill-kotlin-code-review",
        "code-review/bill-kotlin-code-review-architecture",
      ),
      pointerKeysByPack["platform-packs/kotlin/platform.yaml"].orEmpty(),
    )
    assertEquals(
      setOf(
        "code-review/bill-kmp-code-review",
        "code-review/bill-kmp-code-review-ui",
      ),
      pointerKeysByPack["platform-packs/kmp/platform.yaml"].orEmpty(),
    )
  }

  @Test
  fun `applyCascade rewrites platform yaml removing baseline and pointer entries`() {
    val repoRoot = seedRepo()
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val service = skillbill.domain.skillremove.SkillRemove(fs)
    val request = SkillRemovalRequest(
      // SKILL-49: `bill-code-review` is a horizontal product skill; cascade-removal tests
      // exercise the maintainer path (`--allow-shipped`). The desktop UI never offers this.
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-review", allowShipped = true),
      repoRootAbsolutePath = repoRoot.toString(),
    )

    service.executeRemoval(request)

    val kmpManifest = Files.readString(repoRoot.resolve("platform-packs/kmp/platform.yaml"))
    assertTrue(
      "baseline:" !in kmpManifest,
      "kmp platform.yaml still references a baseline after removal:\n$kmpManifest",
    )
    assertTrue(
      "bill-kmp-code-review" !in kmpManifest,
      "kmp platform.yaml still references removed code-review skills:\n$kmpManifest",
    )
    val kotlinManifest = Files.readString(repoRoot.resolve("platform-packs/kotlin/platform.yaml"))
    assertTrue(
      "bill-kotlin-code-review" !in kotlinManifest,
      "kotlin platform.yaml still references removed code-review skills:\n$kotlinManifest",
    )
  }

  @Test
  fun `applyCascade leaves platform packs in a state the schema accepts on reload`() {
    val repoRoot = seedRepo()
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val service = skillbill.domain.skillremove.SkillRemove(fs)
    val request = SkillRemovalRequest(
      // SKILL-49: `bill-code-review` is a horizontal product skill; cascade-removal tests
      // exercise the maintainer path (`--allow-shipped`). The desktop UI never offers this.
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-review", allowShipped = true),
      repoRootAbsolutePath = repoRoot.toString(),
    )

    service.executeRemoval(request)

    // Re-parse both manifests through the same production loader the repo browser's
    // `AuthoringDiscovery` path uses. This is the real assertion: the repo browser's `buildTree`
    // path will succeed if and only if this succeeds.
    val packs = skillbill.scaffold.discoverPlatformPackManifests(repoRoot.resolve("platform-packs"))
    val kmpPack = packs.first { it.slug == "kmp" }
    val kotlinPack = packs.first { it.slug == "kotlin" }
    assertEquals(null, kmpPack.declaredFiles.baseline, "kmp baseline should be null after code-review removal")
    assertEquals(null, kotlinPack.declaredFiles.baseline, "kotlin baseline should be null after code-review removal")
    assertEquals(emptyMap(), kmpPack.declaredFiles.areas)
    assertEquals(emptyList(), kmpPack.declaredCodeReviewAreas)
    assertEquals(null, kmpPack.routedSkillName)
    // The kotlin pack should keep its quality-check feature intact.
    assertNotNull(kotlinPack.declaredQualityCheckFile, "kotlin pack should retain its quality-check file")
  }

  @Test
  fun `executeRemoval PlatformPack deletes the platform pack root and paired pre-shell tree`() {
    val repoRoot = seedRepo()
    seedSkillDir(repoRoot.resolve("skills/kotlin"))
    val packRoot = repoRoot.resolve("platform-packs/kotlin")
    val pairedPreShellRoot = repoRoot.resolve("skills/kotlin")
    val otherPackRoot = repoRoot.resolve("platform-packs/kmp")
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val service = skillbill.domain.skillremove.SkillRemove(fs)
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.PlatformPack(platform = "kotlin"),
      repoRootAbsolutePath = repoRoot.toString(),
    )

    service.executeRemoval(request)

    assertTrue(!Files.exists(packRoot, LinkOption.NOFOLLOW_LINKS), "platform pack root should be deleted")
    assertTrue(!Files.exists(pairedPreShellRoot, LinkOption.NOFOLLOW_LINKS), "paired pre-shell tree should be deleted")
    assertTrue(Files.isDirectory(otherPackRoot, LinkOption.NOFOLLOW_LINKS), "unrelated platform pack should remain")
  }

  @Test
  fun `planManifestEdits emits REMOVE_DECLARED_QUALITY_CHECK_FILE when a quality-check override exists`() {
    val repoRoot = seedRepo()
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.HorizontalSkill(skillName = "bill-code-check", allowShipped = true),
      repoRootAbsolutePath = repoRoot.toString(),
    )

    val edits = fs.planManifestEdits(request, listOf("bill-code-check", "bill-kotlin-code-check"))

    assertTrue(
      edits.any {
        it.manifestPath == "platform-packs/kotlin/platform.yaml" &&
          it.editKind == ManifestEditKind.REMOVE_DECLARED_QUALITY_CHECK_FILE
      },
      "expected REMOVE_DECLARED_QUALITY_CHECK_FILE for kotlin pack, got: $edits",
    )
  }

  @Test
  fun `executeRemoval AddOn removes platform and skill-class references`() {
    val (repoRoot, addon) = seedRepoWithAddonReferences()
    val fs = SkillRemoveJvmFileSystem(home = Files.createTempDirectory("home").also(tempDirs::add))
    val service = skillbill.domain.skillremove.SkillRemove(fs)
    val request = SkillRemovalRequest(
      target = SkillRemovalTarget.AddOn("platform-packs/kmp/addons/android-compose-edge-to-edge.md"),
      repoRootAbsolutePath = repoRoot.toString(),
    )

    service.executeRemoval(request)

    assertTrue(!Files.exists(addon, LinkOption.NOFOLLOW_LINKS), "add-on file should be deleted")
    val platformManifest = Files.readString(repoRoot.resolve("platform-packs/kmp/platform.yaml"))
    assertTrue("android-compose-edge-to-edge.md" !in platformManifest, platformManifest)
    assertTrue("android-compose-review.md" in platformManifest, platformManifest)
    val skillClassManifest = Files.readString(repoRoot.resolve("orchestration/skill-classes/feature-implement.yaml"))
    assertTrue("android-compose-edge-to-edge" !in skillClassManifest, skillClassManifest)
    assertTrue("android-navigation-implementation" in skillClassManifest, skillClassManifest)
  }

  private fun seedRepoWithAddonReferences(): Pair<Path, Path> {
    val repoRoot = seedRepo()
    val addon = repoRoot.resolve("platform-packs/kmp/addons/android-compose-edge-to-edge.md")
    Files.createDirectories(addon.parent)
    Files.writeString(addon, "# Edge to edge\n")
    Files.createDirectories(repoRoot.resolve("orchestration/skill-classes"))
    Files.writeString(repoRoot.resolve("orchestration/skill-classes/feature-implement.yaml"), ADDON_SKILL_CLASS_YAML)
    Files.writeString(repoRoot.resolve("platform-packs/kmp/platform.yaml"), KMP_PLATFORM_YAML_WITH_ADDON_REFERENCES)
    return repoRoot to addon
  }

  private fun seedRepo(): Path {
    val repoRoot = Files.createTempDirectory("skillbill-skill-remove-fs").also(tempDirs::add)
    seedSkillDir(repoRoot.resolve("skills/bill-code-review"))
    seedSkillDir(repoRoot.resolve("skills/bill-code-check"))
    seedSkillDir(repoRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review"))
    seedSkillDir(repoRoot.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-architecture"))
    seedSkillDir(repoRoot.resolve("platform-packs/kotlin/quality-check/bill-kotlin-code-check"))
    seedSkillDir(repoRoot.resolve("platform-packs/kmp/code-review/bill-kmp-code-review"))
    seedSkillDir(repoRoot.resolve("platform-packs/kmp/code-review/bill-kmp-code-review-ui"))
    Files.writeString(repoRoot.resolve("platform-packs/kotlin/platform.yaml"), KOTLIN_PLATFORM_YAML)
    Files.writeString(repoRoot.resolve("platform-packs/kmp/platform.yaml"), KMP_PLATFORM_YAML)
    return repoRoot
  }

  private fun seedSkillDir(dir: Path) {
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("content.md"), "# placeholder\n")
  }

  private companion object {
    private val KOTLIN_PLATFORM_YAML = """
      |platform: kotlin
      |contract_version: "1.1"
      |routing_signals:
      |  strong:
      |    - ".kt"
      |declared_code_review_areas:
      |  - architecture
      |declared_files:
      |  baseline: code-review/bill-kotlin-code-review/content.md
      |  areas:
      |    architecture: code-review/bill-kotlin-code-review-architecture/content.md
      |area_metadata:
      |  architecture:
      |    focus: "architecture"
      |declared_quality_check_file: quality-check/bill-kotlin-code-check/content.md
      |pointers:
      |  code-review/bill-kotlin-code-review:
      |    - name: shell-ceremony.md
      |      target: orchestration/shell-content-contract/shell-ceremony.md
      |  code-review/bill-kotlin-code-review-architecture:
      |    - name: shell-ceremony.md
      |      target: orchestration/shell-content-contract/shell-ceremony.md
      |  quality-check/bill-kotlin-code-check:
      |    - name: shell-ceremony.md
      |      target: orchestration/shell-content-contract/shell-ceremony.md
      |
    """.trimMargin()

    private val KMP_PLATFORM_YAML = """
      |platform: kmp
      |contract_version: "1.1"
      |routing_signals:
      |  strong:
      |    - "androidMain"
      |declared_code_review_areas:
      |  - ui
      |declared_files:
      |  baseline: code-review/bill-kmp-code-review/content.md
      |  areas:
      |    ui: code-review/bill-kmp-code-review-ui/content.md
      |area_metadata:
      |  ui:
      |    focus: "ui"
      |pointers:
      |  code-review/bill-kmp-code-review:
      |    - name: shell-ceremony.md
      |      target: orchestration/shell-content-contract/shell-ceremony.md
      |  code-review/bill-kmp-code-review-ui:
      |    - name: shell-ceremony.md
      |      target: orchestration/shell-content-contract/shell-ceremony.md
      |
    """.trimMargin()

    private val ADDON_SKILL_CLASS_YAML = """
      |class: feature-implement
      |contract_version: "1.1"
      |matchers:
      |  - exact: bill-feature-task
      |pointers:
      |  - shell-ceremony
      |  - android-compose-edge-to-edge
      |  - android-navigation-implementation
      |ceremony_lines: []
      |
    """.trimMargin()

    private val KMP_PLATFORM_YAML_WITH_ADDON_REFERENCES = """
      |platform: kmp
      |contract_version: "1.1"
      |routing_signals:
      |  strong:
      |    - "androidMain"
      |declared_code_review_areas: []
      |declared_files:
      |  baseline: code-review/bill-kmp-code-review/content.md
      |  areas: {}
      |area_metadata: {}
      |pointers:
      |  code-review/bill-kmp-code-review:
      |    - name: shell-ceremony.md
      |      target: orchestration/shell-content-contract/shell-ceremony.md
      |    - name: android-compose-edge-to-edge.md
      |      target: platform-packs/kmp/addons/android-compose-edge-to-edge.md
      |    - name: android-compose-review.md
      |      target: platform-packs/kmp/addons/android-compose-review.md
      |addon_usage:
      |  code-review/bill-kmp-code-review:
      |    - slug: android-compose
      |      entrypoint: android-compose-review.md
      |      companion_pointers:
      |        - android-compose-edge-to-edge.md
      |        - android-navigation-review.md
      |
    """.trimMargin()
  }
}
