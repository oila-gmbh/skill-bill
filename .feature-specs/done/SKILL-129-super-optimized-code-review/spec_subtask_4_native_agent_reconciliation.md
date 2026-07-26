# Subtask 4 - Reconcile and preflight native agents

## Scope

Repair provider-neutral native-agent declarations, rendering, installation,
managed-link reconciliation, and runtime preflight so every planned worker has
one current resolvable artifact and obsolete dangling managed links disappear.

## Acceptance Criteria

1. Native-agent source and the flattened launch planner agree on the complete
   logical worker set; validation rejects a planned but undeclared worker and an
   ambiguous duplicate target.
2. Install atomically renders and links selected provider artifacts, verifies
   logical name/content digest/readability, and removes obsolete or dangling
   Skill Bill-managed links by reconciling the complete managed-link inventory,
   not merely current generated filenames, without deleting unmanaged user
   files.
3. Runtime preflight verifies every selected worker resolves into the current
   installed cache/staging before launch and returns a typed failure plus repair
   command when it does not.
4. No missing Kotlin/KMP baseline target silently falls back to a
   `general-purpose` worker or carries a misleading baseline label.
5. Install/reconcile tests seed dangling `bill-kotlin-code-review.toml` and
   `bill-kmp-code-review.toml` links, stale hashes, missing specialist artifacts,
   duplicate targets, and unmanaged files, then verify safe deterministic
   outcomes across supported provider layouts.

## Non-Goals

- Do not keep baseline orchestrator native agents when flattened planning no
  longer names them solely for compatibility.
- Do not delete unmanaged custom agents.
- Do not change specialist review rubrics.

## Dependency Notes

Depends on subtask 3 for the authoritative logical worker set and launch-plan
shape.

## Validation Strategy

Run native-agent parser/composition, install plan/apply/reconcile, symlink,
provider-layout, rendered-agent, and agent-config validation tests. Run
`./install.sh` in an isolated test home and assert there are no dangling managed
targets.

## Next Path

Continue with subtask 5, which exposes trustworthy accounting and proves the
optimized flow end to end.
