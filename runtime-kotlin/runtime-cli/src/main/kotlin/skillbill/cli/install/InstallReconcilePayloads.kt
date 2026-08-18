package skillbill.cli.install

import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.SkillReconciliationOutcome

/**
 * Typed presenter for the reconcile machine-readable report. Renders the
 * [ReconciliationPlan] into the JSON shape install.sh consumes: a per-skill `outcomes`
 * array (each `{path, outcome, upstream_hash?, local_hash?, baseline_hash?}`). No raw-map
 * public surface: the CLI builds the wire map at the emission boundary only.
 */
internal fun reconcilePayload(
  plan: ReconciliationPlan,
  refreshed: Boolean,
  applied: Boolean = false,
  installedPaths: List<String> = emptyList(),
  prunedPaths: List<String> = emptyList(),
): Map<String, Any?> = mapOf(
  "status" to "ok",
  "applied" to applied,
  "baseline_refreshed" to refreshed,
  "installed_paths" to installedPaths,
  "pruned_paths" to prunedPaths,
  "outcomes" to plan.outcomes.map(::reconcileOutcomeWireMap),
)

/**
 * STABLE, line-oriented machine report that install.sh consumes line-by-line — mirroring
 * the existing `install claude-roots` / goal_event line protocol. Each skill outcome is
 * one `reconcile_outcome:` key=value line and the decision is a single
 * `reconcile_summary:` line. install.sh reads `reconcile_summary:` for the decision
 * (FAIL-CLOSED when absent/unparseable).
 *
 * `path` is emitted as the LAST token on each `reconcile_outcome:` line so install.sh can
 * recover paths containing spaces by taking the trailing remainder after `path=` rather
 * than truncating at the first space.
 *
 * Format (one space-separated key=value per token, kind values are the hyphenated
 * outcome names):
 *   reconcile_outcome: kind=<adopt|unchanged|prune|locally-authored> [upstream_hash=<hex>] path=<p>
 *   reconcile_summary: applied=<bool> baseline_refreshed=<bool> installed_count=<n>
 *     pruned_count=<n>
 */
internal fun reconcileMachineReport(
  plan: ReconciliationPlan,
  refreshed: Boolean,
  applied: Boolean,
  installedPaths: List<String>,
  prunedPaths: List<String>,
): String = buildString {
  plan.outcomes.forEach { outcome ->
    append("reconcile_outcome: kind=")
    append(reconcileOutcomeKind(outcome))
    reconcileOutcomeUpstreamHash(outcome)?.let { hash ->
      append(" upstream_hash=")
      append(hash)
    }
    append(" path=")
    append(outcome.skillRelativePath)
    append('\n')
  }
  append("reconcile_summary: applied=")
  append(applied)
  append(" baseline_refreshed=")
  append(refreshed)
  append(" installed_count=")
  append(installedPaths.size)
  append(" pruned_count=")
  append(prunedPaths.size)
  append('\n')
}

private fun reconcileOutcomeKind(outcome: SkillReconciliationOutcome): String = when (outcome) {
  is SkillReconciliationOutcome.Adopt -> "adopt"
  is SkillReconciliationOutcome.Unchanged -> "unchanged"
  is SkillReconciliationOutcome.Prune -> "prune"
  is SkillReconciliationOutcome.LocallyAuthored -> "locally-authored"
}

private fun reconcileOutcomeUpstreamHash(outcome: SkillReconciliationOutcome): String? = when (outcome) {
  is SkillReconciliationOutcome.Adopt -> outcome.upstreamHash
  is SkillReconciliationOutcome.Unchanged -> outcome.upstreamHash
  is SkillReconciliationOutcome.Prune -> null
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
  is SkillReconciliationOutcome.Unchanged -> mapOf(
    "path" to outcome.skillRelativePath,
    "outcome" to "unchanged",
    "upstream_hash" to outcome.upstreamHash,
    "local_hash" to outcome.upstreamHash,
    "baseline_hash" to outcome.baselineHash,
  )
  is SkillReconciliationOutcome.Prune -> mapOf(
    "path" to outcome.skillRelativePath,
    "outcome" to "prune",
    "local_hash" to outcome.localHash,
    "baseline_hash" to outcome.baselineHash,
  )
  is SkillReconciliationOutcome.LocallyAuthored -> mapOf(
    "path" to outcome.skillRelativePath,
    "outcome" to "locally-authored",
    "local_hash" to outcome.localHash,
    "baseline_hash" to outcome.baselineHash,
  )
}
