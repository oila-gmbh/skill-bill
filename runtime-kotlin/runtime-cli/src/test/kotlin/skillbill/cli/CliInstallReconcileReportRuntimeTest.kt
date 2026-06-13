package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-76 Subtask 2 (testing F-001/B): contract test for the STABLE line-oriented
 * machine report install.sh consumes. Drives the REAL `install reconcile` command
 * through the real CliOutput emit path (CliRuntime.run -> completeText) for a known
 * plan and asserts the exact `reconcile_outcome:`/`reconcile_summary:` lines, so the
 * shell parser is tested against the real bytes and the fake-CLI stub format can never
 * silently drift from the real format.
 */
class CliInstallReconcileReportRuntimeTest {
  private fun seedSkill(repoRoot: Path, name: String, body: String) {
    val skillDir = repoRoot.resolve("skills/$name")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      "---\nname: $name\ndescription: Test skill.\n---\n\n$body\n",
    )
  }

  private fun context(home: Path): CliRuntimeContext = CliRuntimeContext(userHome = home, environment = emptyMap())

  @Test
  fun `real reconcile command emits the stable line report for a clean first-install plan`() {
    val home = Files.createTempDirectory("skillbill-reconcile-report-home")
    val upstream = Files.createTempDirectory("skillbill-reconcile-report-upstream")
    val local = home.resolve(".skill-bill")
    seedSkill(upstream, "bill-sample", "Upstream body.")
    Files.createDirectories(upstream.resolve("platform-packs"))
    Files.createDirectories(local.resolve("platform-packs"))

    val result = CliRuntime.run(
      listOf(
        "--home", home.toString(),
        "install", "reconcile",
        "--repo-root", local.toString(),
        "--skills", local.resolve("skills").toString(),
        "--platform-packs", local.resolve("platform-packs").toString(),
        "--upstream-repo-root", upstream.toString(),
        "--upstream-skills", upstream.resolve("skills").toString(),
        "--upstream-platform-packs", upstream.resolve("platform-packs").toString(),
      ),
      context(home),
    )

    assertEquals(0, result.exitCode, result.stdout)
    // First install (no baseline, no local copy) -> the sample skill is new-upstream.
    val outcomeLine = result.stdout.lines().single { it.startsWith("reconcile_outcome:") }
    assertContains(outcomeLine, "path=skills/bill-sample")
    assertContains(outcomeLine, "kind=new-upstream")
    assertContains(outcomeLine, "upstream_hash=")
    val summaryLine = result.stdout.lines().single { it.startsWith("reconcile_summary:") }
    assertContains(summaryLine, "applied=false")
    assertContains(summaryLine, "has_conflicts=false")
    assertContains(summaryLine, "conflict_count=0")
  }

  @Test
  fun `real reconcile command emits a conflict line and summary for a both-changed plan`() {
    val home = Files.createTempDirectory("skillbill-reconcile-report-conflict-home")
    val upstream = Files.createTempDirectory("skillbill-reconcile-report-conflict-upstream")
    val local = home.resolve(".skill-bill")
    // Seed identical baseline content, then diverge both sides so the skill is a conflict.
    seedSkill(upstream, "bill-sample", "BASE body.")
    seedSkill(local, "bill-sample", "BASE body.")
    Files.createDirectories(upstream.resolve("platform-packs"))
    Files.createDirectories(local.resolve("platform-packs"))

    // Record the baseline at the shared base content via a first --apply.
    val seed = CliRuntime.run(
      listOf(
        "--home", home.toString(),
        "install", "reconcile", "--apply",
        "--repo-root", local.toString(),
        "--skills", local.resolve("skills").toString(),
        "--platform-packs", local.resolve("platform-packs").toString(),
        "--upstream-repo-root", upstream.toString(),
        "--upstream-skills", upstream.resolve("skills").toString(),
        "--upstream-platform-packs", upstream.resolve("platform-packs").toString(),
      ),
      context(home),
    )
    assertEquals(0, seed.exitCode, seed.stdout)

    // Now diverge BOTH sides from the recorded baseline.
    seedSkill(upstream, "bill-sample", "UPSTREAM divergence.")
    seedSkill(local, "bill-sample", "LOCAL divergence.")

    val result = CliRuntime.run(
      listOf(
        "--home", home.toString(),
        "install", "reconcile",
        "--repo-root", local.toString(),
        "--skills", local.resolve("skills").toString(),
        "--platform-packs", local.resolve("platform-packs").toString(),
        "--upstream-repo-root", upstream.toString(),
        "--upstream-skills", upstream.resolve("skills").toString(),
        "--upstream-platform-packs", upstream.resolve("platform-packs").toString(),
      ),
      context(home),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val conflictLine = result.stdout.lines().single { it.contains("kind=conflict") }
    assertTrue(conflictLine.startsWith("reconcile_outcome: kind=conflict "), conflictLine)
    assertTrue(conflictLine.endsWith(" path=skills/bill-sample"), conflictLine)
    val summaryLine = result.stdout.lines().single { it.startsWith("reconcile_summary:") }
    assertContains(summaryLine, "has_conflicts=true")
    assertContains(summaryLine, "conflict_count=1")
    // The structured payload mirrors the lines (kept for JSON consumers).
    assertEquals(true, result.payload?.get("has_conflicts"))
  }

  @Test
  fun `real reconcile --apply installs the upstream skill and refreshes the baseline`() {
    val home = Files.createTempDirectory("skillbill-reconcile-apply-home")
    val upstream = Files.createTempDirectory("skillbill-reconcile-apply-upstream")
    val local = home.resolve(".skill-bill")
    seedSkill(upstream, "bill-sample", "Upstream body.")
    Files.createDirectories(upstream.resolve("platform-packs"))
    Files.createDirectories(local.resolve("platform-packs"))

    val result = CliRuntime.run(
      listOf(
        "--home", home.toString(),
        "install", "reconcile", "--apply",
        "--repo-root", local.toString(),
        "--skills", local.resolve("skills").toString(),
        "--platform-packs", local.resolve("platform-packs").toString(),
        "--upstream-repo-root", upstream.toString(),
        "--upstream-skills", upstream.resolve("skills").toString(),
        "--upstream-platform-packs", upstream.resolve("platform-packs").toString(),
      ),
      context(home),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val summaryLine = result.stdout.lines().single { it.startsWith("reconcile_summary:") }
    assertContains(summaryLine, "applied=true")
    assertContains(summaryLine, "installed_count=1")
    // The upstream skill was installed into the live tree.
    val liveContent = local.resolve("skills/bill-sample/content.md")
    assertTrue(Files.isRegularFile(liveContent), "apply must install the upstream skill into the live tree")
    assertContains(Files.readString(liveContent), "Upstream body.")
    // The baseline manifest was written.
    assertTrue(Files.isRegularFile(home.resolve(".skill-bill/baseline-manifest.json")))

    // Idempotent re-apply: nothing installed, has_conflicts=false.
    val second = CliRuntime.run(
      listOf(
        "--home", home.toString(),
        "install", "reconcile", "--apply",
        "--repo-root", local.toString(),
        "--skills", local.resolve("skills").toString(),
        "--platform-packs", local.resolve("platform-packs").toString(),
        "--upstream-repo-root", upstream.toString(),
        "--upstream-skills", upstream.resolve("skills").toString(),
        "--upstream-platform-packs", upstream.resolve("platform-packs").toString(),
      ),
      context(home),
    )
    assertEquals(0, second.exitCode, second.stdout)
    val secondSummary = second.stdout.lines().single { it.startsWith("reconcile_summary:") }
    assertContains(secondSummary, "installed_count=0")
    assertContains(secondSummary, "has_conflicts=false")
    assertFalse(secondSummary.contains("baseline_refreshed=true"))
  }

  @Test
  fun `real reconcile --apply refuses on conflict without accept and changes nothing`() {
    val home = Files.createTempDirectory("skillbill-reconcile-refuse-home")
    val upstream = Files.createTempDirectory("skillbill-reconcile-refuse-upstream")
    val local = home.resolve(".skill-bill")
    seedSkill(upstream, "bill-sample", "BASE body.")
    seedSkill(local, "bill-sample", "BASE body.")
    Files.createDirectories(upstream.resolve("platform-packs"))
    Files.createDirectories(local.resolve("platform-packs"))

    // Seed baseline at base content.
    CliRuntime.run(
      listOf(
        "--home", home.toString(), "install", "reconcile", "--apply",
        "--repo-root", local.toString(),
        "--skills", local.resolve("skills").toString(),
        "--platform-packs", local.resolve("platform-packs").toString(),
        "--upstream-repo-root", upstream.toString(),
        "--upstream-skills", upstream.resolve("skills").toString(),
        "--upstream-platform-packs", upstream.resolve("platform-packs").toString(),
      ),
      context(home),
    )
    // Diverge both sides.
    seedSkill(upstream, "bill-sample", "UPSTREAM divergence.")
    seedSkill(local, "bill-sample", "LOCAL divergence.")
    val localBytesBefore = Files.readString(local.resolve("skills/bill-sample/content.md"))

    val refused = CliRuntime.run(
      listOf(
        "--home", home.toString(), "install", "reconcile", "--apply",
        "--repo-root", local.toString(),
        "--skills", local.resolve("skills").toString(),
        "--platform-packs", local.resolve("platform-packs").toString(),
        "--upstream-repo-root", upstream.toString(),
        "--upstream-skills", upstream.resolve("skills").toString(),
        "--upstream-platform-packs", upstream.resolve("platform-packs").toString(),
      ),
      context(home),
    )

    assertFalse(refused.exitCode == 0, "apply must refuse on unresolved conflict. Output:\n${refused.stdout}")
    // Nothing changed on disk.
    assertEquals(localBytesBefore, Files.readString(local.resolve("skills/bill-sample/content.md")))
  }
}
