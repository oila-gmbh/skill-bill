package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.install.reconcile.ReconcileSourceRoots
import skillbill.install.reconcile.enumerateSkills
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.InstalledWorkspaceBaselineStatusPort
import skillbill.ports.install.baseline.model.InstalledWorkspaceBaselineStatusRequest
import skillbill.ports.install.baseline.model.InstalledWorkspaceBaselineStatusResult
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest

/**
 * SKILL-77 Subtask 4: computes per-skill modified-vs-baseline status for the installed
 * workspace by re-enumerating the live `skills/` + `platform-packs/` trees with the SAME
 * [enumerateSkills]/`computeInstallContentHash` path the reconcile policy uses, then
 * comparing each live hash against the recorded baseline entry. Read-only: it reads the
 * baseline through [BaselineManifestPersistencePort.readBaseline] and never writes.
 */
@Inject
class FileSystemInstalledWorkspaceBaselineStatus(
  private val baselinePersistence: BaselineManifestPersistencePort,
) : InstalledWorkspaceBaselineStatusPort {

  override fun modifiedSkillRelativePaths(
    request: InstalledWorkspaceBaselineStatusRequest,
  ): InstalledWorkspaceBaselineStatusResult {
    val installRoot = request.installRoot.toAbsolutePath().normalize()
    val read = baselinePersistence.readBaseline(ReadBaselineManifestRequest(installHome = request.installHome))
    if (!read.existed) return InstalledWorkspaceBaselineStatusResult(emptySet())
    val baseline = read.manifest

    val roots = ReconcileSourceRoots(
      repoRoot = installRoot,
      skillsRoot = installRoot.resolve("skills"),
      platformPacksRoot = installRoot.resolve("platform-packs"),
    )
    val live = enumerateSkills(roots, home = request.installHome)
    val modified = live.asSequence()
      .filter { (skillRelativePath, entry) -> baseline.hashFor(skillRelativePath) != entry.hash }
      .map { (skillRelativePath, _) -> skillRelativePath }
      .toSet()
    return InstalledWorkspaceBaselineStatusResult(modified)
  }
}
