package skillbill.install

import skillbill.error.ReconciliationApplyRefusedError
import skillbill.install.model.BaselineManifest
import skillbill.install.model.SkillReconciliationOutcome
import skillbill.install.reconcile.ReconcileSourceRoots
import skillbill.install.reconcile.applyReconciliation
import skillbill.install.reconcile.computeReconciliationPlan
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-76 Subtask 2: authoritative apply coverage for the runtime-owned per-skill
 * reconcile APPLY. Reuses [InstallApplyTestSupport]'s seed helpers and the real
 * `computeInstallContentHash` (via [computeReconciliationPlan]) to assert per-outcome
 * FILE results: adopt overwrites the live skill with the UPSTREAM bytes; keep-local
 * retains the user's edited LOCAL bytes; locally-authored is retained (never deleted);
 * new-upstream is installed; an accepted conflict overwrites. Also asserts conflict
 * gating (apply refuses without accept) and apply idempotency (zero replacements +
 * byte-identical inputs on a second run).
 */
class InstallReconcileApplyTest : InstallApplyTestSupport() {
  private fun roots(repoRoot: Path) = ReconcileSourceRoots(
    repoRoot = repoRoot,
    skillsRoot = repoRoot.resolve("skills"),
    platformPacksRoot = repoRoot.resolve("platform-packs"),
  )

  private fun seedRepo(name: String): Path {
    val repoRoot = Files.createTempDirectory(name).also(tempDirs::add)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedBaseSkill(repoRoot, "bill-code-check")
    Files.createDirectories(repoRoot.resolve("platform-packs"))
    return repoRoot
  }

  private fun home(): Path = Files.createTempDirectory("skillbill-apply-home").also(tempDirs::add)

  private fun baselineFromUpstream(upstream: Path, home: Path): BaselineManifest {
    val seedPlan = computeReconciliationPlan(roots(upstream), roots(upstream), home, BaselineManifest.empty())
    val entries = seedPlan.outcomes.filterIsInstance<SkillReconciliationOutcome.NewUpstream>()
      .associate { it.skillRelativePath to it.upstreamHash }
    return BaselineManifest.of(BaselineManifest.CONTRACT_VERSION, entries)
  }

  private fun reviewContent(repoRoot: Path): Path = repoRoot.resolve("skills/bill-code-review/content.md")

  @Test
  fun `adopt overwrites live skill with upstream bytes and keep-local retains local edit`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    // Upstream changes bill-code-review (adopt: local untouched); local edits
    // bill-code-check (keep-local: upstream untouched).
    val upstreamReviewBytes = content("bill-code-review") + "\nUPSTREAM REVIEW\n"
    Files.writeString(reviewContent(upstream), upstreamReviewBytes)
    val localCheckBytes = content("bill-code-check") + "\nLOCAL CHECK EDIT\n"
    Files.writeString(local.resolve("skills/bill-code-check/content.md"), localCheckBytes)

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline, acceptConflicts = false)

    // adopt: the live review skill now holds the UPSTREAM bytes.
    assertEquals(upstreamReviewBytes, Files.readString(reviewContent(local)))
    assertTrue(output.installedPaths.contains("skills/bill-code-review"))
    // keep-local: the user's edited check skill bytes survive untouched.
    assertEquals(localCheckBytes, Files.readString(local.resolve("skills/bill-code-check/content.md")))
    assertFalse(output.installedPaths.contains("skills/bill-code-check"))
    // baseline refresh set covers adopt only.
    assertEquals(listOf("skills/bill-code-review"), output.plan.baselineRefreshPaths)
  }

  @Test
  fun `locally-authored skill is retained and never deleted`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)
    // Local has an extra skill upstream does not ship.
    seedBaseSkill(local, "bill-local-only")
    val localOnly = local.resolve("skills/bill-local-only/content.md")
    val localOnlyBytes = Files.readString(localOnly)

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline, acceptConflicts = false)

    assertTrue(Files.isRegularFile(localOnly), "locally-authored skill must never be deleted")
    assertEquals(localOnlyBytes, Files.readString(localOnly))
    assertFalse(output.installedPaths.contains("skills/bill-local-only"))
    assertFalse(output.plan.baselineRefreshPaths.contains("skills/bill-local-only"))
  }

  @Test
  fun `new-upstream installs a skill into a missing live tree`() {
    val upstream = seedRepo("apply-upstream")
    // Empty local tree (first install): no skills dir yet.
    val localRepo = Files.createTempDirectory("apply-local-empty").also(tempDirs::add)
    val home = home()

    val output =
      applyReconciliation(roots(upstream), roots(localRepo), home, BaselineManifest.empty(), acceptConflicts = false)

    assertTrue(Files.isRegularFile(reviewContent(localRepo)), "new-upstream must install the skill into the live tree")
    assertEquals(content("bill-code-review"), Files.readString(reviewContent(localRepo)))
    assertTrue(output.installedPaths.contains("skills/bill-code-review"))
    assertTrue(output.installedPaths.contains("skills/bill-code-check"))
  }

  @Test
  fun `adopt restores a missing live skill when baseline exists`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    Files.walk(local.resolve("skills/bill-code-review")).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline, acceptConflicts = false)

    assertTrue(Files.isRegularFile(reviewContent(local)), "missing live skill must be restored from upstream")
    assertEquals(content("bill-code-review"), Files.readString(reviewContent(local)))
    assertTrue(output.installedPaths.contains("skills/bill-code-review"))
    assertEquals(listOf("skills/bill-code-review"), output.plan.baselineRefreshPaths)
  }

  @Test
  fun `conflict refuses without accept and changes nothing`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    Files.writeString(reviewContent(upstream), content("bill-code-review") + "\nUPSTREAM\n")
    val localBytes = content("bill-code-review") + "\nLOCAL\n"
    Files.writeString(reviewContent(local), localBytes)

    assertFailsWith<ReconciliationApplyRefusedError> {
      applyReconciliation(roots(upstream), roots(local), home, baseline, acceptConflicts = false)
    }
    // Nothing changed: the live conflicting skill keeps its local bytes.
    assertEquals(localBytes, Files.readString(reviewContent(local)))
  }

  @Test
  fun `accepted conflict overwrites the live skill with upstream bytes`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    val upstreamBytes = content("bill-code-review") + "\nUPSTREAM WINS\n"
    Files.writeString(reviewContent(upstream), upstreamBytes)
    Files.writeString(reviewContent(local), content("bill-code-review") + "\nLOCAL\n")

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline, acceptConflicts = true)

    assertEquals(upstreamBytes, Files.readString(reviewContent(local)))
    assertTrue(output.installedPaths.contains("skills/bill-code-review"))
    assertTrue(output.plan.baselineRefreshPaths.contains("skills/bill-code-review"))
  }

  @Test
  fun `platform-pack skill keep-local survives apply and pack-level metadata is adopted`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    seedPlatformPack(upstream, "kotlin")
    seedPlatformPack(local, "kotlin")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    // User edits a platform-pack skill content file locally (local!=baseline,
    // upstream==baseline) -> keep-local; the apply must NOT clobber it.
    val packSkill = local.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md")
    val userBytes = Files.readString(packSkill) + "\nUSER PACK EDIT\n"
    Files.writeString(packSkill, userBytes)
    // Upstream ships a new non-skill pack addon file (no baseline, adopt-always).
    Files.writeString(upstream.resolve("platform-packs/kotlin/ADDON.md"), "# upstream addon\n")

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline, acceptConflicts = false)

    assertEquals(userBytes, Files.readString(packSkill), "keep-local platform-pack skill edit must survive apply")
    assertFalse(output.installedPaths.contains("platform-packs/kotlin/code-review/bill-kotlin-code-review"))
    // Non-skill pack addon was adopted from upstream into the live tree.
    assertContains(Files.readString(local.resolve("platform-packs/kotlin/ADDON.md")), "upstream addon")
  }

  @Test
  fun `platform-pack skill both-changed is a conflict not a silent overwrite`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    seedPlatformPack(upstream, "kotlin")
    seedPlatformPack(local, "kotlin")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    val packPath = "platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md"
    Files.writeString(upstream.resolve(packPath), Files.readString(upstream.resolve(packPath)) + "\nUPSTREAM\n")
    val localBytes = Files.readString(local.resolve(packPath)) + "\nLOCAL\n"
    Files.writeString(local.resolve(packPath), localBytes)

    assertFailsWith<ReconciliationApplyRefusedError> {
      applyReconciliation(roots(upstream), roots(local), home, baseline, acceptConflicts = false)
    }
    assertEquals(
      localBytes,
      Files.readString(local.resolve(packPath)),
      "an unaccepted pack conflict must change nothing",
    )
  }

  @Test
  fun `apply is idempotent - second run installs nothing and leaves bytes identical`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    // First install: empty baseline -> all new-upstream.
    val first =
      applyReconciliation(roots(upstream), roots(local), home, BaselineManifest.empty(), acceptConflicts = false)
    assertTrue(first.installedPaths.isNotEmpty())
    val refreshed = BaselineManifest.of(
      BaselineManifest.CONTRACT_VERSION,
      first.plan.outcomes.filterIsInstance<SkillReconciliationOutcome.NewUpstream>()
        .associate { it.skillRelativePath to it.upstreamHash },
    )
    val reviewBytesAfterFirst = Files.readString(reviewContent(local))

    // Second run with the refreshed baseline and unchanged inputs: keep-local/no-op,
    // zero replacements, byte-identical live tree.
    val second = applyReconciliation(roots(upstream), roots(local), home, refreshed, acceptConflicts = false)
    assertTrue(second.installedPaths.isEmpty(), "idempotent apply must perform zero replacements")
    assertTrue(second.plan.baselineRefreshPaths.isEmpty(), "idempotent apply must not refresh any baseline entry")
    assertEquals(reviewBytesAfterFirst, Files.readString(reviewContent(local)))
  }
}
