package skillbill.ports.install.reconcile.model

import skillbill.install.model.ReconciliationPlan
import java.nio.file.Path

/**
 * SKILL-76 Subtask 2: request for the reconcile-compute port. Carries the two
 * source roots being compared plus the install home (used both to resolve the
 * baseline manifest and to assemble per-skill content hashes exactly like
 * `InstallPlanBuilder.buildStagingIntent`).
 *
 * - [upstreamRepoRoot]/[upstreamSkillsRoot]/[upstreamPlatformPacksRoot]: the clone
 *   (or staged candidate) being copied in. Skills are enumerated from these roots.
 * - [localRepoRoot]/[localSkillsRoot]/[localPlatformPacksRoot]: the copied
 *   `~/.skill-bill` source the user may have edited.
 * - [home]: install home; the adapter resolves
 *   `<home>/.skill-bill/baseline-manifest.json` through the baseline persistence port.
 */
data class InstallReconcileRequest(
  val home: Path,
  val upstreamRepoRoot: Path,
  val upstreamSkillsRoot: Path,
  val upstreamPlatformPacksRoot: Path,
  val localRepoRoot: Path,
  val localSkillsRoot: Path,
  val localPlatformPacksRoot: Path,
)

data class InstallReconcileResult(
  val plan: ReconciliationPlan,
)
