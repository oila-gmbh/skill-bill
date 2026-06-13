package skillbill.install

import skillbill.infrastructure.fs.FileSystemBaselineManifestPersistence
import skillbill.infrastructure.fs.FileSystemInstalledWorkspaceBaselineStatus
import skillbill.install.model.BaselineManifest
import skillbill.install.reconcile.ReconcileSourceRoots
import skillbill.install.reconcile.enumerateSkills
import skillbill.ports.install.baseline.model.InstalledWorkspaceBaselineStatusRequest
import skillbill.ports.install.baseline.model.WriteBaselineManifestRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKILL-77 Subtask 4: exercises the installed-workspace modified-vs-baseline status against
 * the REAL SKILL-76 wire format and `computeInstallContentHash` hasher — baselines are
 * captured with [enumerateSkills] and persisted through [FileSystemBaselineManifestPersistence],
 * exactly as install does, so a match/mismatch/add/remove/missing case reflects production.
 */
class FileSystemInstalledWorkspaceBaselineStatusTest : InstallApplyTestSupport() {
  private val persistence = FileSystemBaselineManifestPersistence()
  private val status = FileSystemInstalledWorkspaceBaselineStatus(persistence)

  private fun roots(installRoot: Path) = ReconcileSourceRoots(
    repoRoot = installRoot,
    skillsRoot = installRoot.resolve("skills"),
    platformPacksRoot = installRoot.resolve("platform-packs"),
  )

  private fun seedInstalledWorkspace(name: String): Pair<Path, Path> {
    val home = Files.createTempDirectory("skillbill-$name-home").also(tempDirs::add)
    val installRoot = home.resolve(".skill-bill")
    seedBaseSkill(installRoot, "bill-alpha", nativeAgentName = "alpha-agent")
    seedBaseSkill(installRoot, "bill-beta")
    Files.createDirectories(installRoot.resolve("platform-packs"))
    return home to installRoot
  }

  private fun captureBaselineFromLive(installRoot: Path, home: Path) {
    val entries = enumerateSkills(roots(installRoot), home).mapValues { (_, entry) -> entry.hash }
    persistence.writeBaseline(
      WriteBaselineManifestRequest(
        installHome = home,
        manifest = BaselineManifest.of(BaselineManifest.CONTRACT_VERSION, entries),
      ),
    )
  }

  private fun modified(installRoot: Path, home: Path): Set<String> = status.modifiedSkillRelativePaths(
    InstalledWorkspaceBaselineStatusRequest(installRoot = installRoot, installHome = home),
  ).modifiedSkillRelativePaths

  @Test
  fun `unmodified workspace reports no locally-modified skills`() {
    val (home, installRoot) = seedInstalledWorkspace("unmodified")
    captureBaselineFromLive(installRoot, home)

    assertEquals(emptySet(), modified(installRoot, home))
  }

  @Test
  fun `editing a skill file flips only that skill modified`() {
    val (home, installRoot) = seedInstalledWorkspace("edited")
    captureBaselineFromLive(installRoot, home)

    Files.writeString(installRoot.resolve("skills/bill-alpha/content.md"), content("bill-alpha") + "\nlocal edit\n")

    assertEquals(setOf("skills/bill-alpha"), modified(installRoot, home))
  }

  @Test
  fun `adding a file under a skill marks it modified`() {
    val (home, installRoot) = seedInstalledWorkspace("added")
    captureBaselineFromLive(installRoot, home)

    Files.writeString(installRoot.resolve("skills/bill-beta/extra.md"), "extra authored file\n")

    assertTrue("skills/bill-beta" in modified(installRoot, home))
  }

  @Test
  fun `removing a file under a skill marks it modified`() {
    val (home, installRoot) = seedInstalledWorkspace("removed")
    captureBaselineFromLive(installRoot, home)

    Files.delete(installRoot.resolve("skills/bill-alpha/native-agents/alpha-agent.md"))

    assertEquals(setOf("skills/bill-alpha"), modified(installRoot, home))
  }

  @Test
  fun `missing baseline manifest yields no indicators and no error`() {
    val (home, installRoot) = seedInstalledWorkspace("no-manifest")

    assertEquals(emptySet(), modified(installRoot, home))
  }
}
