package skillbill.install

import skillbill.infrastructure.fs.FileSystemBaselineManifestPersistence
import skillbill.install.model.BaselineManifest
import skillbill.install.model.SkillReconciliationOutcome
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.install.baseline.model.WriteBaselineManifestRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKILL-76 Subtask 2: unit tests for the reconcile hash-compare policy and the
 * baseline manifest persistence adapter. Reuses [InstallApplyTestSupport]'s
 * seed helpers and exercises all five outcomes (adopt / keep-local / conflict /
 * new-upstream / locally-authored) plus idempotency and a baseline round-trip,
 * all keyed off the shared `computeInstallContentHash`.
 */
class InstallReconcileTest : InstallApplyTestSupport() {
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

  private fun home(): Path = Files.createTempDirectory("skillbill-reconcile-home").also(tempDirs::add)

  private fun planWith(upstream: Path, local: Path, home: Path, baseline: BaselineManifest) =
    computeReconciliationPlan(roots(upstream), roots(local), home, baseline)

  private fun emptyBaselinePlan(upstream: Path, local: Path, home: Path) =
    planWith(upstream, local, home, BaselineManifest.empty())

  private fun outcomeFor(plan: skillbill.install.model.ReconciliationPlan, path: String) =
    plan.outcomes.single { it.skillRelativePath == path }

  private fun baselineFromUpstream(upstream: Path, local: Path, home: Path): BaselineManifest {
    val seedPlan = emptyBaselinePlan(upstream, local, home)
    val entries = seedPlan.outcomes.filterIsInstance<SkillReconciliationOutcome.NewUpstream>()
      .associate { it.skillRelativePath to it.upstreamHash }
    return BaselineManifest.of(BaselineManifest.CONTRACT_VERSION, entries)
  }

  @Test
  fun `no baseline yields new-upstream for every skill`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()

    val plan = emptyBaselinePlan(upstream, local, home)

    assertTrue(plan.outcomes.isNotEmpty())
    assertTrue(plan.outcomes.all { it is SkillReconciliationOutcome.NewUpstream })
    assertFalse(plan.hasConflicts)
  }

  @Test
  fun `unmodified local adopts new upstream and refreshes baseline`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    // Baseline == the original (unmodified) content for both skills.
    val baseline = baselineFromUpstream(upstream, local, home)

    // Upstream changes bill-code-review only; local stays equal to baseline.
    Files.writeString(
      upstream.resolve("skills/bill-code-review/content.md"),
      content("bill-code-review") + "\nupstream\n",
    )

    val plan = planWith(upstream, local, home, baseline)

    val adopt = assertIs<SkillReconciliationOutcome.Adopt>(outcomeFor(plan, "skills/bill-code-review"))
    assertEquals(baseline.hashFor("skills/bill-code-review"), adopt.localHash)
    assertEquals(baseline.hashFor("skills/bill-code-review"), adopt.baselineHash)
    assertTrue(adopt.upstreamHash != adopt.baselineHash)
    // The untouched skill is a no-op keep-local.
    assertIs<SkillReconciliationOutcome.KeepLocal>(outcomeFor(plan, "skills/bill-code-check"))
    assertFalse(plan.hasConflicts)
    assertEquals(listOf("skills/bill-code-review"), plan.baselineRefreshPaths)
  }

  @Test
  fun `user edit survives when upstream is unchanged`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    // Local edits bill-code-review; upstream stays equal to baseline.
    Files.writeString(local.resolve("skills/bill-code-review/content.md"), content("bill-code-review") + "\nlocal\n")

    val plan = planWith(upstream, local, home, baseline)

    val keep = assertIs<SkillReconciliationOutcome.KeepLocal>(outcomeFor(plan, "skills/bill-code-review"))
    assertEquals(baseline.hashFor("skills/bill-code-review"), keep.upstreamHash)
    assertTrue(keep.localHash != keep.baselineHash)
    assertFalse(plan.hasConflicts)
    // keep-local must NOT appear in the refresh set.
    assertFalse(plan.baselineRefreshPaths.contains("skills/bill-code-review"))
  }

  @Test
  fun `no baseline with a divergent local copy is a conflict not a silent overwrite`() {
    // Migration window: an existing user has a populated local copy but no baseline yet.
    // When the local copy diverges from upstream the local edit must NOT be silently
    // overwritten -> the skill is classified as a conflict (WARN + prompt; no-TTY aborts).
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()

    Files.writeString(local.resolve("skills/bill-code-review/content.md"), content("bill-code-review") + "\nlocal\n")

    val plan = planWith(upstream, local, home, BaselineManifest.empty())

    val conflict = assertIs<SkillReconciliationOutcome.Conflict>(outcomeFor(plan, "skills/bill-code-review"))
    assertTrue(conflict.localHash != conflict.upstreamHash)
    assertTrue(plan.hasConflicts)
    // The unmodified skill (local == upstream, no baseline) stays new-upstream, no prompt.
    assertIs<SkillReconciliationOutcome.NewUpstream>(outcomeFor(plan, "skills/bill-code-check"))
  }

  @Test
  fun `no baseline with an absent local copy stays new-upstream (first install unaffected)`() {
    // The first-install path (no live skills dir -> local hash null) must remain
    // new-upstream and never trip the migration-window conflict guard.
    val upstream = seedRepo("reconcile-upstream")
    val localRepo = Files.createTempDirectory("reconcile-local-empty").also(tempDirs::add)
    val home = home()

    val plan = planWith(upstream, localRepo, home, BaselineManifest.empty())

    assertTrue(plan.outcomes.isNotEmpty())
    assertTrue(plan.outcomes.all { it is SkillReconciliationOutcome.NewUpstream })
    assertFalse(plan.hasConflicts)
  }

  @Test
  fun `both-changed produces a conflict`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    Files.writeString(
      upstream.resolve("skills/bill-code-review/content.md"),
      content("bill-code-review") + "\nupstream\n",
    )
    Files.writeString(local.resolve("skills/bill-code-review/content.md"), content("bill-code-review") + "\nlocal\n")

    val plan = planWith(upstream, local, home, baseline)

    val conflict = assertIs<SkillReconciliationOutcome.Conflict>(outcomeFor(plan, "skills/bill-code-review"))
    assertTrue(conflict.localHash != conflict.baselineHash)
    assertTrue(conflict.upstreamHash != conflict.baselineHash)
    assertTrue(plan.hasConflicts)
    assertEquals(listOf("skills/bill-code-review"), plan.conflicts.map { it.skillRelativePath })
  }

  @Test
  fun `locally authored skill without upstream counterpart is preserved`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    // Local has an extra skill that upstream does not ship.
    seedBaseSkill(local, "bill-local-only")

    val plan = planWith(upstream, local, home, baseline)

    val authored = assertIs<SkillReconciliationOutcome.LocallyAuthored>(outcomeFor(plan, "skills/bill-local-only"))
    // Testing F-005: assert the localHash EQUALS the real content hash of the seeded
    // source, not merely that it is a hex string.
    val expectedLocalHash = computeReconciliationPlan(roots(local), roots(local), home, BaselineManifest.empty())
      .outcomes.single { it.skillRelativePath == "skills/bill-local-only" }
      .let { assertIs<SkillReconciliationOutcome.NewUpstream>(it).upstreamHash }
    assertEquals(expectedLocalHash, authored.localHash)
    assertFalse(plan.hasConflicts)
    assertFalse(plan.baselineRefreshPaths.contains("skills/bill-local-only"))
  }

  @Test
  fun `identical inputs are idempotent with no conflicts and no refresh`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    val plan = planWith(upstream, local, home, baseline)

    assertTrue(plan.outcomes.all { it is SkillReconciliationOutcome.KeepLocal })
    assertFalse(plan.hasConflicts)
    assertTrue(plan.baselineRefreshPaths.isEmpty())
  }

  @Test
  fun `baseline persistence round-trips with sorted byte-stable writes`() {
    val home = home()
    val persistence = FileSystemBaselineManifestPersistence()

    // Missing manifest reads as empty + existed=false (first install).
    val initial = persistence.readBaseline(ReadBaselineManifestRequest(home))
    assertFalse(initial.existed)
    assertTrue(initial.manifest.entries.isEmpty())

    val manifest = BaselineManifest.of(
      BaselineManifest.CONTRACT_VERSION,
      mapOf(
        "skills/bill-zebra" to "00112233aabbccdd",
        "skills/bill-alpha" to "ffeeddccbbaa9988",
      ),
    )
    val writeResult = persistence.writeBaseline(WriteBaselineManifestRequest(home, manifest))
    val firstBytes = Files.readAllBytes(writeResult.path)

    val readBack = persistence.readBaseline(ReadBaselineManifestRequest(home))
    assertTrue(readBack.existed)
    assertEquals(manifest.entries, readBack.manifest.entries)

    // Sorted keys: alpha must serialize before zebra.
    val text = String(firstBytes)
    assertTrue(text.indexOf("bill-alpha") < text.indexOf("bill-zebra"), "baseline keys must be sorted: $text")

    // Byte-stable: re-writing the same manifest yields byte-identical output (no churn).
    persistence.writeBaseline(WriteBaselineManifestRequest(home, readBack.manifest))
    val secondBytes = Files.readAllBytes(writeResult.path)
    assertTrue(firstBytes.contentEquals(secondBytes), "no-change rewrite must be byte-identical")
  }
}
