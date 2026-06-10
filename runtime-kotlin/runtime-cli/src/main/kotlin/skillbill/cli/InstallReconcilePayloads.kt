package skillbill.cli

import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.SkillReconciliationOutcome

/**
 * SKILL-76 Subtask 2: typed presenter for the reconcile machine-readable report.
 * Renders the [ReconciliationPlan] into the JSON shape install.sh consumes:
 * a per-skill `outcomes` array (each `{path, outcome, upstream_hash?, local_hash?,
 * baseline_hash?}`) plus a flat `conflicts` array of conflicting skill-relative
 * paths and a `has_conflicts` boolean. No raw-map public surface: the CLI builds
 * the wire map at the emission boundary only.
 */
internal fun reconcilePayload(
  plan: ReconciliationPlan,
  refreshed: Boolean,
  applied: Boolean = false,
  installedPaths: List<String> = emptyList(),
): Map<String, Any?> = mapOf(
  "status" to "ok",
  "applied" to applied,
  "has_conflicts" to plan.hasConflicts,
  "baseline_refreshed" to refreshed,
  "installed_paths" to installedPaths,
  "conflicts" to plan.conflicts.map(SkillReconciliationOutcome.Conflict::skillRelativePath),
  "outcomes" to plan.outcomes.map(::reconcileOutcomeWireMap),
)

/**
 * SKILL-76 Subtask 2: STABLE, line-oriented machine report that install.sh consumes
 * line-by-line — mirroring the existing `install claude-roots` / goal_event line
 * protocol. Each skill outcome is one `reconcile_outcome:` key=value line and the
 * decision is a single `reconcile_summary:` line. install.sh reads `reconcile_summary:`
 * for the decision (FAIL-CLOSED when absent/unparseable) and the
 * `reconcile_outcome: kind=conflict ...` lines for the conflict summary.
 *
 * `path` is emitted as the LAST token on each `reconcile_outcome:` line so install.sh can
 * recover paths containing spaces by taking the trailing remainder after `path=` rather
 * than truncating at the first space; `kind` is anchored ahead of `path` so a conflict
 * filter cannot collide with a path that happens to contain `kind=conflict`.
 *
 * Format (one space-separated key=value per token, kind values are the hyphenated
 * outcome names):
 *   reconcile_outcome: kind=<adopt|keep-local|conflict|new-upstream|locally-authored>
 *     [upstream_hash=<hex>] path=<p>
 *   reconcile_summary: applied=<bool> has_conflicts=<bool> conflict_count=<n>
 *     baseline_refreshed=<bool> installed_count=<n>
 */
internal fun reconcileMachineReport(
  plan: ReconciliationPlan,
  refreshed: Boolean,
  applied: Boolean,
  installedPaths: List<String>,
): String = buildString {
  plan.outcomes.forEach { outcome ->
    append("reconcile_outcome: kind=")
    append(reconcileOutcomeKind(outcome))
    reconcileOutcomeUpstreamHash(outcome)?.let { hash ->
      append(" upstream_hash=")
      append(hash)
    }
    // path is LAST so a path containing spaces survives install.sh's trailing-remainder parse.
    append(" path=")
    append(outcome.skillRelativePath)
    append('\n')
  }
  append("reconcile_summary: applied=")
  append(applied)
  append(" has_conflicts=")
  append(plan.hasConflicts)
  append(" conflict_count=")
  append(plan.conflicts.size)
  append(" baseline_refreshed=")
  append(refreshed)
  append(" installed_count=")
  append(installedPaths.size)
  append('\n')
}

private fun reconcileOutcomeKind(outcome: SkillReconciliationOutcome): String = when (outcome) {
  is SkillReconciliationOutcome.Adopt -> "adopt"
  is SkillReconciliationOutcome.KeepLocal -> "keep-local"
  is SkillReconciliationOutcome.Conflict -> "conflict"
  is SkillReconciliationOutcome.NewUpstream -> "new-upstream"
  is SkillReconciliationOutcome.LocallyAuthored -> "locally-authored"
}

private fun reconcileOutcomeUpstreamHash(outcome: SkillReconciliationOutcome): String? = when (outcome) {
  is SkillReconciliationOutcome.Adopt -> outcome.upstreamHash
  is SkillReconciliationOutcome.KeepLocal -> outcome.upstreamHash
  is SkillReconciliationOutcome.Conflict -> outcome.upstreamHash
  is SkillReconciliationOutcome.NewUpstream -> outcome.upstreamHash
  is SkillReconciliationOutcome.LocallyAuthored -> null
}

private fun reconcileOutcomeWireMap(outcome: SkillReconciliationOutcome): Map<String, Any?> = when (outcome) {
  is SkillReconciliationOutcome.Adopt -> mapOf(
    "path" to outcome.skillRelativePath,
    "outcome" to "adopt",
    "upstream_hash" to outcome.upstreamHash,
    "local_hash" to outcome.localHash,
    "baseline_hash" to outcome.baselineHash,
  )
  is SkillReconciliationOutcome.KeepLocal -> mapOf(
    "path" to outcome.skillRelativePath,
    "outcome" to "keep-local",
    "upstream_hash" to outcome.upstreamHash,
    "local_hash" to outcome.localHash,
    "baseline_hash" to outcome.baselineHash,
  )
  is SkillReconciliationOutcome.Conflict -> mapOf(
    "path" to outcome.skillRelativePath,
    "outcome" to "conflict",
    "upstream_hash" to outcome.upstreamHash,
    "local_hash" to outcome.localHash,
    "baseline_hash" to outcome.baselineHash,
  )
  is SkillReconciliationOutcome.NewUpstream -> mapOf(
    "path" to outcome.skillRelativePath,
    "outcome" to "new-upstream",
    "upstream_hash" to outcome.upstreamHash,
    "local_hash" to outcome.localHash,
  )
  is SkillReconciliationOutcome.LocallyAuthored -> mapOf(
    "path" to outcome.skillRelativePath,
    "outcome" to "locally-authored",
    "local_hash" to outcome.localHash,
    "baseline_hash" to outcome.baselineHash,
  )
}
