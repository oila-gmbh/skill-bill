# Observability Policy

Every fallback, degradation, and swallowed failure emits a record. A silent
fallback is a defect: it produces the symptom of a bug with none of the evidence.

Emit an observability event, structured log line, or telemetry field at each of
these seams:

- a `?:`, `takeIf`, `getOrNull`, `orEmpty`, or default-value path that
  substitutes a narrower or wider scope than the caller asked for
- a `runCatching` or `catch` that continues instead of rethrowing
- a retry, timeout, cap, truncation, or sampling decision
- a capability that resolves to absent and is skipped
- spec-intent resolution that records `spec_context: none` (`no_spec_found`, `ambiguous_match`, `not_applicable_scope`) or falls through from an unreadable decomposition manifest to branch-derived glob search; records carry reason, rung, and resolved path only, never spec body
- a skipped adjudication stage, a verification or adjudication worker that failed to launch or return, and a stage that ended without a reached boundary; each emits `skillbill_review_stage_degradation` with seam, expected, actual, and a closed reason, carrying `review_run_id` only
- a legacy-record migration, quarantine, or regeneration
- a reconciliation that repairs drift between durable state and disk
- checkpoint-ref prune: `FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs`
  when listing or deleting a ref under `refs/skill-bill/checkpoints/` fails, or when
  pruning is skipped because `commit_sha` is still blank
- platform-pack `contract_version` leniency: `CanonicalPlatformPackSchemaValidator.validate`
  when a caller enumerates with `enforceContractVersion=false` (reconcile's LOCAL side and
  installed-workspace baseline status) and a stale `const` violation is tolerated instead of
  raising `ContractVersionMismatchError`

Each record names the seam, the value actually used, the value that was expected,
and why the substitution happened. A fallback that cannot be attributed to a
specific cause is a loud-fail, not a log line.

Absent platform-pack `validation_gate` declarations degrade validate to agent-run
behavior at seam `ValidationGateResolver.resolve` / `feature-task.validate.validation_gate.absent`
with a surfaced record; a malformed declaration loud-fails and never degrades to
"no gate".

Prefer loud-fail over log-and-continue whenever the substituted value changes a
contract the caller depends on — scope bounds, review deltas, staged path
inventories, and durable identity are contracts. Log-and-continue is for
degradations the caller can still trust.

Bounded output rules still apply: records carry counts, identities, and sanitized
labels, never raw payloads, diff hunks, or unbounded child output.
