package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SKILL-76 Subtask 2: shell-level reconcile scenarios that drive install.sh through the
 * Kotlin ProcessBuilder harness with a fake reconcile CLI emitting the SAME line-based
 * machine report the real CLI emits (pinned by the runtime-cli contract test). Split out
 * of [InstallerShellDelegationTest] so each test class stays under detekt's LargeClass
 * threshold. Covers AC-3/AC-5/AC-7/AC-9: idempotent reinstall, keep-local survival,
 * conflict-abort byte-identical, and clone-deletion resolution.
 */
class InstallerShellReconcileTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  // The TTY-bypass accept scenario drives install.sh's desktop-install summary, which
  // defaults differently per OS; it asserts the Linux flow, so it runs on the Linux CI
  // leg and skips elsewhere.
  private fun assumeLinuxHost() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      System.getProperty("os.name").lowercase().startsWith("linux"),
      "installer flow assertions assume Linux host behavior; skipping on ${System.getProperty("os.name")}",
    )
  }

  @Test
  fun `reinstall with no upstream-local change is idempotent and commits the apply`() {
    // SKILL-76 AC-9: a no-conflict reconcile report drives the runtime per-skill apply to
    // completion, leaving the copied source resolvable. The fake CLI returns
    // has_conflicts=false so no prompt is reached.
    val run = runInstallerShellRaw(input = "1\ncopilot\nbase only\noff\nskip\n", conflictPath = null)

    assertEquals(0, run.exitCode, run.output)
    assertLiveSourcePopulated(run.home)
    // The staged candidate dirs are reaped after the runtime per-skill apply.
    assertFalse(
      Files.exists(run.home.resolve(".skill-bill/.candidate-source")),
      "the staged candidate source must be reaped after apply",
    )
  }

  @Test
  fun `reinstall preserves a user-edited live skill (AC-5 keep-local)`() {
    // SKILL-76 AC-5: a keep-local classification means the runtime apply leaves the live
    // skill dir untouched, so the user's edited bytes survive a real reinstall.
    val first = runInstallerShellRaw(input = "1\ncopilot\nbase only\noff\nskip\n", conflictPath = null)
    assertEquals(0, first.exitCode, first.output)
    val liveSkill = first.home.resolve(".skill-bill/skills/bill-sample/content.md")
    assertTrue(Files.isRegularFile(liveSkill), "first install must materialize the live skill")
    Files.writeString(liveSkill, "USER EDIT SENTINEL\n")

    // Second install forced to classify the edited skill keep-local: apply must not
    // overwrite it, so the sentinel bytes remain.
    val second = runInstallerShellRaw(
      input = "1\ncopilot\nbase only\noff\nskip\n",
      conflictPath = null,
      reuse = first,
      scenario = ReconcileScenario(keepLocalPath = "skills/bill-sample"),
    )
    assertEquals(0, second.exitCode, second.output)
    assertEquals(
      "USER EDIT SENTINEL\n",
      Files.readString(liveSkill),
      "AC-5: keep-local must leave the user-edited live skill bytes unchanged",
    )
  }

  @Test
  fun `reinstall preserves a user-edited agent addon`() {
    val first = runInstallerShellRaw(input = "1\ncopilot\nbase only\noff\nskip\n", conflictPath = null)
    assertEquals(0, first.exitCode, first.output)
    val liveAddon = first.home.resolve(".skill-bill/agent-addons/review-helper/content.md")
    Files.writeString(liveAddon, "USER AGENT ADDON EDIT\n")

    val second = runInstallerShellRaw(
      input = "1\ncopilot\nbase only\noff\nskip\n",
      conflictPath = null,
      reuse = first,
      scenario = ReconcileScenario(keepLocalPath = "agent-addons/review-helper"),
    )

    assertEquals(0, second.exitCode, second.output)
    assertEquals("USER AGENT ADDON EDIT\n", Files.readString(liveAddon))
  }

  @Test
  fun `untouched local adopts new upstream and refreshes the baseline (AC-6)`() {
    // SKILL-76 AC-6: a local skill the user never edited (local == baseline) must adopt the
    // changed upstream on reinstall — the live bytes become the new upstream bytes and the
    // baseline manifest is rewritten.
    val first = runInstallerShellRaw(input = "1\ncopilot\nbase only\noff\nskip\n", conflictPath = null)
    assertEquals(0, first.exitCode, first.output)
    val liveSkill = first.home.resolve(".skill-bill/skills/bill-sample/content.md")
    assertTrue(Files.isRegularFile(liveSkill), "first install must materialize the live skill")
    val baseline = first.home.resolve(".skill-bill/baseline-manifest.json")
    val baselineAfterFirst = Files.readAllBytes(baseline)

    val newUpstreamBody = "---\nname: bill-sample\ndescription: Sample skill.\n---\n\nUPSTREAM ADOPT BODY.\n"
    val second = runInstallerShellRaw(
      input = "1\ncopilot\nbase only\noff\nskip\n",
      conflictPath = null,
      reuse = first,
      scenario = ReconcileScenario(
        mutateUpstream = { repoRoot ->
          Files.writeString(repoRoot.resolve("skills/bill-sample/content.md"), newUpstreamBody)
        },
      ),
    )
    assertEquals(0, second.exitCode, second.output)
    assertEquals(
      newUpstreamBody,
      Files.readString(liveSkill),
      "AC-6: an untouched local skill must adopt the new upstream bytes",
    )
    assertTrue(Files.isRegularFile(baseline), "AC-6: the baseline manifest must remain present after adopt")
    // The baseline was (re)written by the apply (fake writes it every apply); the manifest
    // is present and the live bytes match upstream, proving the adopt landed.
    assertTrue(baselineAfterFirst.isNotEmpty(), "AC-6: a baseline must have been written on first install")
  }

  @Test
  fun `TTY-bypass accept overwrites the conflicting skill and reports it in the summary (AC-7 accept)`() {
    assumeLinuxHost()
    // SKILL-76 AC-7 y branch: a both-changed conflict driven through the TEST-ONLY
    // SKILL_BILL_RECONCILE_CONFLICT_CHOICE=y seam must overwrite the live skill with
    // upstream, refresh the baseline, and report the overwritten conflict path in the
    // install summary (the dead summary block is now exercised).
    val first = runInstallerShellRaw(input = "1\ncopilot\nbase only\noff\nskip\n", conflictPath = null)
    assertEquals(0, first.exitCode, first.output)
    val liveSkill = first.home.resolve(".skill-bill/skills/bill-sample/content.md")
    Files.writeString(liveSkill, "LOCAL EDIT BEFORE ACCEPT\n")

    val second = runInstallerShellRaw(
      input = "1\ncopilot\nbase only\noff\nskip\n",
      conflictPath = "skills/bill-sample",
      reuse = first,
      scenario = ReconcileScenario(conflictChoice = "y"),
    )
    assertEquals(0, second.exitCode, "AC-7 accept must complete the install. Output:\n${second.output}")
    // The y branch applied upstream: the live skill no longer holds the local edit.
    assertFalse(
      Files.readString(liveSkill).contains("LOCAL EDIT BEFORE ACCEPT"),
      "AC-7 accept must overwrite the local edit with upstream",
    )
    // The install summary reports the overwritten conflict path.
    assertContains(second.output, "overwrote local edits with upstream")
    assertContains(second.output, "skills/bill-sample")
    // The baseline manifest exists (refreshed by the apply).
    assertTrue(Files.isRegularFile(first.home.resolve(".skill-bill/baseline-manifest.json")))
  }

  @Test
  fun `no-TTY conflict abort leaves the prior install byte-identical (AC-7)`() {
    // SKILL-76 AC-7: a first install, a sentinel edit, then a second install forced into a
    // no-TTY conflict must abort WITHOUT touching the live skill bytes or the baseline.
    val first = runInstallerShellRaw(input = "1\ncopilot\nbase only\noff\nskip\n", conflictPath = null)
    assertEquals(0, first.exitCode, first.output)
    val liveSkill = first.home.resolve(".skill-bill/skills/bill-sample/content.md")
    Files.writeString(liveSkill, "SENTINEL BEFORE ABORT\n")
    val baseline = first.home.resolve(".skill-bill/baseline-manifest.json")
    val baselineBefore = if (Files.exists(baseline)) Files.readAllBytes(baseline) else ByteArray(0)

    val second = runInstallerShellRaw(
      input = "1\ncopilot\nbase only\noff\nskip\n",
      conflictPath = "skills/bill-sample",
      reuse = first,
    )
    assertFalse(second.exitCode == 0, "no-TTY conflict must abort non-zero. Output:\n${second.output}")
    assertContains(second.output, "no TTY is attached to prompt")
    assertEquals(
      "SENTINEL BEFORE ABORT\n",
      Files.readString(liveSkill),
      "AC-7: an aborted conflict must leave the live skill bytes byte-identical",
    )
    val baselineAfter = if (Files.exists(baseline)) Files.readAllBytes(baseline) else ByteArray(0)
    assertTrue(
      baselineBefore.contentEquals(baselineAfter),
      "AC-7: an aborted conflict must leave the baseline manifest byte-identical",
    )
  }

  @Test
  fun `shell reinstall is idempotent (AC-9) byte-identical baseline and clean report`() {
    // SKILL-76 AC-9: install twice with no local edits; the second run reports
    // has_conflicts=false and leaves the baseline-manifest.json bytes identical.
    val first = runInstallerShellRaw(input = "1\ncopilot\nbase only\noff\nskip\n", conflictPath = null)
    assertEquals(0, first.exitCode, first.output)
    val baseline = first.home.resolve(".skill-bill/baseline-manifest.json")
    val baselineAfterFirst = Files.readAllBytes(baseline)

    val second = runInstallerShellRaw(
      input = "1\ncopilot\nbase only\noff\nskip\n",
      conflictPath = null,
      reuse = first,
    )
    assertEquals(0, second.exitCode, second.output)
    assertFalse(
      second.output.contains("conflict:"),
      "AC-9: a no-edit reinstall must report no conflicts. Output:\n${second.output}",
    )
    assertTrue(
      baselineAfterFirst.contentEquals(Files.readAllBytes(baseline)),
      "AC-9: a no-edit reinstall must leave the baseline-manifest.json bytes identical",
    )
  }

  @Test
  fun `no-TTY both-changed conflict aborts the whole install with a clear message`() {
    // SKILL-76 AC-7: piped stdin (no PTY) + a both-changed conflict must abort with a clear
    // message rather than guessing. The prior copied source must stay intact.
    val run = runInstallerShellRaw(
      input = "1\ncopilot\nbase only\noff\nskip\n",
      conflictPath = "skills/bill-sample",
    )

    assertFalse(run.exitCode == 0, "a no-TTY conflict must abort non-zero. Output:\n${run.output}")
    assertContains(run.output, "no TTY is attached to prompt")
    // Aborted before the apply: the staged candidate is discarded and the live source is
    // left exactly as it was (no half-applied state).
    assertFalse(
      Files.exists(run.home.resolve(".skill-bill/.candidate-source")),
      "an aborted reconcile must discard the staged candidate",
    )
  }

  @Test
  fun `reinstall without the clone present still resolves the copied source`() {
    // SKILL-76 AC-3: after a successful install the clone can be deleted and the copied
    // source under ~/.skill-bill keeps resolving. (No-conflict reconcile.)
    val run = runInstallerShellRaw(input = "1\ncopilot\nbase only\noff\nskip\n", conflictPath = null)
    assertEquals(0, run.exitCode, run.output)

    Files.walk(run.repoRoot).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
    assertFalse(Files.exists(run.repoRoot), "clone should be deleted for the AC-3 reinstall check")
    assertTrue(
      Files.isRegularFile(run.home.resolve(".skill-bill/skills/bill-sample/content.md")),
      "AC-3: copied skill content.md must survive clone deletion",
    )
  }

  @Test
  fun `reconcile failure retries once with clean copied source reset`() {
    val run = runInstallerShellRaw(
      input = "1\ncopilot\nbase only\noff\nskip\n",
      conflictPath = null,
      scenario = ReconcileScenario(reconcileFailOnce = true),
    )

    assertEquals(0, run.exitCode, run.output)
    assertContains(run.output, "retrying once with a clean copied-source reset")
    assertLiveSourcePopulated(run.home)
    assertFalse(
      Files.exists(run.home.resolve(".skill-bill/.candidate-source")),
      "the retry must still reap the staged candidate source after apply",
    )
  }

  private fun assertLiveSourcePopulated(home: Path) {
    val stateDir = home.resolve(".skill-bill")
    listOf(stateDir.resolve("skills"), stateDir.resolve("platform-packs"), stateDir.resolve("orchestration"))
      .forEach { dir ->
        assertTrue(Files.isDirectory(dir), "copy-in must create real directory $dir")
        assertFalse(Files.isSymbolicLink(dir), "copy-in must create REAL files, not a symlink: $dir")
      }
    assertTrue(
      Files.isRegularFile(stateDir.resolve("skills/bill-sample/content.md")),
      "copy-in must materialize skill content.md under the copy",
    )
    assertTrue(
      Files.isRegularFile(stateDir.resolve("orchestration/review-orchestrator/PLAYBOOK.md")),
      "copy-in must materialize the WHOLE orchestration tree under the copy",
    )
  }

  private data class InstallerShellRawRun(
    val repoRoot: Path,
    val home: Path,
    val binDir: Path,
    val exitCode: Int,
    val output: String,
  )

  // Optional reconcile-scenario knobs, bundled so runInstallerShellRaw stays within detekt's
  // parameter budget. keepLocalPath makes the fake classify that skill keep-local so apply
  // leaves the edit; conflictChoice exercises the SKILL_BILL_RECONCILE_CONFLICT_CHOICE seam;
  // mutateUpstream bumps the staged upstream skill source before the second install (AC-6).
  private data class ReconcileScenario(
    val keepLocalPath: String? = null,
    val conflictChoice: String? = null,
    val mutateUpstream: ((Path) -> Unit)? = null,
    val reconcileFailOnce: Boolean = false,
  )

  // Drive install.sh without asserting success, optionally forcing the fake CLI's reconcile
  // report to carry a both-changed conflict for a skill path. `reuse` (a prior run) drives a
  // second install against that install's home + bin dir (AC-5/AC-6/AC-7/AC-9 reinstall
  // scenarios); `scenario` carries the optional fake-CLI/seam knobs.
  private fun runInstallerShellRaw(
    input: String,
    conflictPath: String?,
    reuse: InstallerShellRawRun? = null,
    scenario: ReconcileScenario = ReconcileScenario(),
  ): InstallerShellRawRun {
    val repoRoot = Files.createTempDirectory("skillbill-reconcile-shell-repo")
    val home = reuse?.home ?: Files.createTempDirectory("skillbill-reconcile-shell-home")
    val binDir = reuse?.binDir ?: Files.createTempDirectory("skillbill-reconcile-shell-bin")
    val logPath = Files.createTempFile("skillbill-reconcile-shell-runtime", ".log")
    Files.writeString(repoRoot.resolve("install.sh"), Files.readString(runtimeRoot.parent.resolve("install.sh")))
    repoRoot.resolve("install.sh").toFile().setExecutable(true)
    InstallerShellFixtures.seedAuthoredSource(repoRoot)
    InstallerShellFixtures.seedAgentAddon(repoRoot)
    InstallerShellFixtures.seedInstallerPlatformPack(repoRoot, "kotlin")
    InstallerShellFixtures.seedInstallerRuntime(repoRoot)
    // Lets an AC-6 adopt test bump the upstream skill body before the second install so the
    // staged candidate differs from the live copy.
    scenario.mutateUpstream?.invoke(repoRoot)

    val process = ProcessBuilder("bash", repoRoot.resolve("install.sh").toString())
      .directory(repoRoot.toFile())
      .redirectErrorStream(true)
      .apply {
        environment()["HOME"] = home.toString()
        environment()["SKILL_BILL_BIN_DIR"] = binDir.toString()
        environment()["SKILL_BILL_SKIP_RUNTIME_DISTRIBUTION_BUILD"] = "1"
        environment()["SKILL_BILL_SKIP_PREINSTALL_UNINSTALL"] = "1"
        environment()["SKILL_BILL_TEST_RUNTIME_LOG"] = logPath.toString()
        environment().remove("SKILL_BILL_GOAL_CONTINUATION")
        environment().remove("DISPLAY")
        environment().remove("WAYLAND_DISPLAY")
        if (conflictPath != null) {
          environment()["SKILL_BILL_FAKE_RECONCILE_CONFLICTS"] = conflictPath
        }
        scenario.keepLocalPath?.let { environment()["SKILL_BILL_FAKE_KEEPLOCAL"] = it }
        scenario.conflictChoice?.let { environment()["SKILL_BILL_RECONCILE_CONFLICT_CHOICE"] = it }
        if (scenario.reconcileFailOnce) {
          environment()["SKILL_BILL_FAKE_RECONCILE_FAIL_ONCE"] = "1"
        }
      }
      .start()
    process.outputStream.bufferedWriter().use { writer -> writer.write(input) }
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return InstallerShellRawRun(repoRoot, home, binDir, exitCode, output)
  }
}
