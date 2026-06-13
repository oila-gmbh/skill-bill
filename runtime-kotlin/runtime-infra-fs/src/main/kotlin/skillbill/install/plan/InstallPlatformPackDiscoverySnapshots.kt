package skillbill.install.plan

import skillbill.install.model.InstallPlatformPackDiscoverySnapshot
import skillbill.scaffold.model.PlatformManifest

/**
 * Map discovered platform manifests to the typed discovery snapshots the install policy
 * input consumes. Extracted from `InstallPlanBuilder` (it touches no `InstallPlanPolicy`
 * surface, so it is not bound by that file's policy-caller architecture constraint) to keep
 * the builder under detekt's per-file function-count threshold after SKILL-76 Subtask 2
 * added the `enumerateInstallPlanSkills` reconcile seam.
 */
internal fun List<PlatformManifest>.toDiscoverySnapshots(): List<InstallPlatformPackDiscoverySnapshot> =
  map { manifest ->
    InstallPlatformPackDiscoverySnapshot(
      slug = manifest.slug,
      packRoot = manifest.packRoot,
    )
  }
