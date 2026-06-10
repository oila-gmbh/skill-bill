package skillbill.ports.install.reconcile.model

import skillbill.install.model.ReconciliationPlan
import java.nio.file.Path

/**
 * SKILL-76 Subtask 2: request for the runtime-owned per-skill reconcile APPLY.
 *
 * Apply recomputes the [ReconciliationPlan] ONCE from the same upstream/local/baseline
 * inputs the compute port uses, then performs the per-skill FILE operations in the
 * adapter:
 *  - adopt / new-upstream / accepted-conflict -> install the UPSTREAM (candidate) skill
 *    dir into the live (`local`) tree atomically per skill (stage to a temp sibling, then
 *    atomic-move/replace the individual skill dir; never a whole-tree rm).
 *  - keep-local / locally-authored            -> leave the live skill dir untouched
 *    (locally-authored skills are NEVER deleted).
 *
 * The live tree (`local`) IS the base; only changed skills are replaced, so keep-local +
 * locally-authored are preserved by construction.
 *
 * CONFLICT GATING: when the computed plan has conflicts and [acceptConflicts] is false,
 * apply REFUSES with a typed loud-fail error and changes nothing — guaranteeing AC-7
 * "abort changes nothing" even if the shell mis-calls.
 *
 * The adapter performs ONLY the per-skill file operations + conflict gating. The baseline
 * manifest refresh is owned by the APPLICATION SERVICE (`InstallService.applyReconcile` ->
 * `refreshBaselineFromPlan`), which derives it from the SAME returned
 * [ReconciliationPlan.baselineRefreshPaths]; see [InstallReconcileApplyResult].
 */
data class InstallReconcileApplyRequest(
  val home: Path,
  val upstreamRepoRoot: Path,
  val upstreamSkillsRoot: Path,
  val upstreamPlatformPacksRoot: Path,
  val localRepoRoot: Path,
  val localSkillsRoot: Path,
  val localPlatformPacksRoot: Path,
  val acceptConflicts: Boolean,
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
)
