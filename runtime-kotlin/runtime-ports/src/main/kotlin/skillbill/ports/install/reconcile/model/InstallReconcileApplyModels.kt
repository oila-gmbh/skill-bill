package skillbill.ports.install.reconcile.model

import skillbill.install.model.ReconciliationPlan
import java.nio.file.Path

/**
 * Request for the runtime-owned per-skill reconcile APPLY.
 *
 * Apply recomputes the [ReconciliationPlan] ONCE from the same upstream/local inputs the
 * compute port uses, then performs the per-skill FILE operations in the adapter:
 *  - adopt              -> install the UPSTREAM (candidate) skill dir into the live
 *    (`local`) tree atomically per skill (stage to a temp sibling, then atomic-move/replace
 *    the individual skill dir; never a whole-tree rm).
 *  - unchanged          -> nothing to write; upstream and local are byte-identical.
 *  - prune              -> delete the live `skills/` or `platform-packs/` dir upstream no
 *    longer ships, so the installed tree mirrors the source.
 *  - locally-authored   -> a user-owned `agent-addons/` entry; left untouched (NEVER deleted).
 *
 * The adapter performs ONLY the per-skill file operations. The baseline manifest refresh
 * is owned by the APPLICATION SERVICE (`InstallService.applyReconcile` ->
 * `refreshBaselineFromPlan`), which derives it from the SAME returned
 * [ReconciliationPlan.baselineOverlay]; see [InstallReconcileApplyResult].
 */
data class InstallReconcileApplyRequest(
  val home: Path,
  val upstreamRepoRoot: Path,
  val upstreamSkillsRoot: Path,
  val upstreamPlatformPacksRoot: Path,
  val localRepoRoot: Path,
  val localSkillsRoot: Path,
  val localPlatformPacksRoot: Path,
)

/**
 * Result of a runtime-owned per-skill apply: the computed plan plus the skill-relative
 * paths whose live dir was actually replaced from upstream (the install summary and tests
 * assert against this). The baseline-manifest refresh is NOT reported here — it is owned by
 * the application service, which computes whether the manifest changed by reading it before
 * and after the refresh.
 */
data class InstallReconcileApplyResult(
  val plan: ReconciliationPlan,
  val installedPaths: List<String>,
  val prunedPaths: List<String>,
)
