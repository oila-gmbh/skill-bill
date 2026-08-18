package skillbill.install

import skillbill.infrastructure.fs.FileSystemBaselineManifestPersistence
import skillbill.install.model.BaselineManifest
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.SkillReconciliationOutcome
import skillbill.install.reconcile.ReconcileSourceRoots
import skillbill.install.reconcile.computeReconciliationPlan
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
 * Unit tests for the reconcile hash-compare policy and the baseline manifest persistence
 * adapter. Reuses [InstallApplyTestSupport]'s seed helpers; all hashes come from the
 * shared `computeInstallContentHash`.
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

  private fun outcomeFor(plan: ReconciliationPlan, path: String) = plan.outcomes.single { it.skillRelativePath == path }

  private fun baselineFromUpstream(upstream: Path, local: Path, home: Path): BaselineManifest = BaselineManifest.of(
    BaselineManifest.CONTRACT_VERSION,
    planWith(upstream, local, home, BaselineManifest.empty()).baselineOverlay,
  )

  @Test
  fun `upstream overwrites a local edit whether or not a baseline exists`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    // bill-code-review: local edited, upstream untouched — the old keep-local case.
    Files.writeString(local.resolve("skills/bill-code-review/content.md"), content("bill-code-review") + "\nlocal\n")
    // bill-code-check: both sides changed — the old conflict case.
    Files.writeString(local.resolve("skills/bill-code-check/content.md"), content("bill-code-check") + "\nlocal\n")
    Files.writeString(
      upstream.resolve("skills/bill-code-check/content.md"),
      content("bill-code-check") + "\nupstream\n",
    )

    val withBaseline = planWith(upstream, local, home, baseline)
    val withoutBaseline = planWith(upstream, local, home, BaselineManifest.empty())

    listOf(withBaseline, withoutBaseline).forEach { plan ->
      listOf("skills/bill-code-review", "skills/bill-code-check").forEach { path ->
        val adopt = assertIs<SkillReconciliationOutcome.Adopt>(outcomeFor(plan, path))
        assertTrue(adopt.localHash != adopt.upstreamHash)
        assertEquals(adopt.upstreamHash, plan.baselineOverlay[path])
      }
    }
  }

  @Test
  fun `skill missing from the local copy is reinstalled from upstream`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    Files.walk(local.resolve("skills/bill-code-review")).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    val plan = planWith(upstream, local, home, baseline)

    val adopt = assertIs<SkillReconciliationOutcome.Adopt>(outcomeFor(plan, "skills/bill-code-review"))
    assertEquals(null, adopt.localHash)
    assertEquals(adopt.upstreamHash, plan.baselineOverlay["skills/bill-code-review"])
  }

  @Test
  fun `a skill upstream no longer ships is pruned but a user-owned addon is preserved`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    seedBaseSkill(local, "bill-local-only")
    seedAgentAddon(local, "local-helper", "LOCAL ONLY\n")

    val plan = planWith(upstream, local, home, baseline)

    val pruned = assertIs<SkillReconciliationOutcome.Prune>(outcomeFor(plan, "skills/bill-local-only"))
    val expectedLocalHash = planWith(local, local, home, BaselineManifest.empty())
      .baselineOverlay
      .getValue("skills/bill-local-only")
    assertEquals(expectedLocalHash, pruned.localHash)
    assertEquals(listOf("skills/bill-local-only"), plan.prunedPaths)
    assertFalse(plan.baselineOverlay.containsKey("skills/bill-local-only"))
    assertIs<SkillReconciliationOutcome.LocallyAuthored>(outcomeFor(plan, "agent-addons/local-helper"))
  }

  @Test
  fun `identical inputs are unchanged with no baseline churn`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    val plan = planWith(upstream, local, home, baseline)

    assertTrue(plan.outcomes.isNotEmpty())
    assertTrue(plan.outcomes.all { it is SkillReconciliationOutcome.Unchanged })
    assertEquals(baseline.entries, baseline.withEntries(plan.baselineOverlay).entries)
  }

  @Test
  fun `agent addon reconcile hashes use baseline manifest hash width`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    seedAgentAddon(upstream, "execution-budget", "UPSTREAM ADDON\n")

    val plan = planWith(upstream, local, home, BaselineManifest.empty())

    val addon = assertIs<SkillReconciliationOutcome.Adopt>(outcomeFor(plan, "agent-addons/execution-budget"))
    assertTrue(
      Regex("^[0-9a-f]{16}$").matches(addon.upstreamHash),
      "agent add-on baseline hashes must use the same 16-hex width as skill hashes: ${addon.upstreamHash}",
    )
  }

  @Test
  fun `baseline persistence round-trips with sorted byte-stable writes`() {
    val home = home()
    val persistence = FileSystemBaselineManifestPersistence()

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

    val text = String(firstBytes)
    assertTrue(text.indexOf("bill-alpha") < text.indexOf("bill-zebra"), "baseline keys must be sorted: $text")

    persistence.writeBaseline(WriteBaselineManifestRequest(home, readBack.manifest))
    val secondBytes = Files.readAllBytes(writeResult.path)
    assertTrue(firstBytes.contentEquals(secondBytes), "no-change rewrite must be byte-identical")
  }

  private fun seedAgentAddon(repo: Path, slug: String, body: String) {
    val root = repo.resolve("agent-addons/$slug")
    Files.createDirectories(root)
    Files.writeString(
      root.resolve("agent-addon.yaml"),
      """
      contract_version: "1.0"
      slug: $slug
      description: Test helper
      agent_ids:
        - codex
      consumers:
        - bill-feature
      """.trimIndent() + "\n",
    )
    Files.writeString(root.resolve("content.md"), body)
  }
}
