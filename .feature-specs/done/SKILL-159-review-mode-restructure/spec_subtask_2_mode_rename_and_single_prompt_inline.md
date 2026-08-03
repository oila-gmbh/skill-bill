# SKILL-159 Subtask 2 — Mode restructure and single-prompt inline review

## Scope

Restructure the review-mode vocabulary on the pruned runtime and add the new
single-prompt review path.

Mode semantics after this subtask:

- `delegated` — the specialist subagent fan-out review (previously named
  `inline`); the default (`CodeReviewExecutionMode.DEFAULT = DELEGATED`).
- `inline` — new single-prompt review: one review prompt over the child-owned
  delta in the current context, no fan-out, no specialists, findings returned
  in the existing severity/report contract so the findings ledger, triage, and
  telemetry consume them unchanged.
- `auto` — pass-number policy: first review pass of a subtask resolves to
  `delegated`, follow-up/remediation passes resolve to `inline`. Update
  `ReviewExecutionModePolicy` rule names to say what they now do.

Epicenter and ripple:

- `CodeReviewExecutionMode.kt` enum, wire values, default; `fromWire` must not
  reinterpret old-semantics values from legacy records (that is the contract
  bump's job).
- `ReviewExecutionModePolicy.kt`, `ReviewContextModels.kt`
  (`ResolvedReviewExecutionMode`), `FeatureTaskRuntimeReviewPassSequence.kt`,
  `GoalSubtaskReviewState.kt`: first pass delegated, remediation pass inline.
- Reported-mode string surface: `ReviewParsingPatterns.kt` execution-mode
  regex, `ReviewParser.kt`, `ReviewModels.kt`, `ReviewStatsModels.kt`,
  `ReviewResults.kt`, `ReviewContractMappers.kt`, `ReviewContracts.kt`.
- CLI: `--code-review-mode` on `FeatureTaskRuntimeCliCommands`,
  `GoalCliCommands`, `CodeReviewParallelCommand` accepts
  `auto|inline|delegated` with the new meanings; duplicate/conflict validation
  preserved.
- Persistence/propagation: `GoalRunnerControlStore`, handoff/continuation
  models, phase prompt composer/directives, goal runner requests — carry the
  new vocabulary end to end.
- Telemetry: `telemetry-event-schema.yaml` `execution_mode` enum,
  `ReviewFinishedTelemetryPayload`, `ReviewSqlConstants`/`execution_mode`
  column values, stats mappers, MCP `review_stats`/`import_review` pass-through.
- Contract bumps: `goal-subtask-review-state-schema.yaml`,
  `review-context-schema.yaml`, `workflow-state-schema.yaml`,
  affected `feature-task-runtime-*-schema.yaml`, `telemetry-event-schema.yaml`
  — bump each changed contract's version const and Kotlin constant together,
  keep parity tests green, and let legacy records loud-fail with the existing
  typed errors so the runtime quarantines and regenerates them in-band.
  In-flight goals do not carry their review mode across the rename; that is
  accepted.

Single-prompt inline content: the runtime's inline path composes one review
prompt (reusing the existing review scope contract: `review_base_sha` +
baseline-untracked subtraction) instead of the specialist fan-out. Authored
prompt content belongs to the governed review content surface; this subtask
lands the runtime seam and a minimal governed prompt, subtask 3 finishes the
content.

## Acceptance Criteria

1. `CodeReviewExecutionMode` wire values are `auto`, `inline`, `delegated` with `DELEGATED` (fan-out) as default; omission of a `code-review:` token resolves to `delegated` at every entry point.
2. `auto` resolves to `delegated` for pass one and `inline` for follow-up/remediation passes, enforced in `ReviewExecutionModePolicy` and `FeatureTaskRuntimeReviewPassSequence`, with rule names updated and covered by updated `ReviewDepthAutoRuleTest`.
3. Selecting `inline` runs exactly one review prompt in the current context — no specialist subagents, no second lane unless `parallel-review:<agent>` is present — and its findings flow through the existing parser, severity model, findings ledger, and `skillbill_review_finished` telemetry.
4. The execution-mode reported-string surface (parser regex through stats mappers) accepts exactly the new token set; unknown or old-semantics tokens produce typed parse failures, not silent mapping.
5. Every schema whose review-mode semantics changed is version-bumped with its Kotlin constant and parity test in lockstep; a legacy persisted record with the old semantics loud-fails with the existing typed error and is quarantined/regenerated in-band on the next read seam.
6. `parallel-review:<agent>` works with both `delegated` and `inline` primary lanes, runtime and prose.
7. `(cd runtime-kotlin && ./gradlew check)` passes, including updated `GoalSubtaskReviewStateTest`, `GoalSubtaskReviewStateLegacyContractTest`, `FeatureTaskRuntimeReviewPassSequenceTest`, `CliFeatureTaskRuntimeRuntimeTest`, and telemetry schema validation tests.

## Non-Goals

- Governed prose/content rewrites beyond the minimal inline prompt seam (subtask 3).
- Reintroducing any external-process launch capability.
- Depth/tier policy changes beyond the pass-number mapping.

## Dependency Notes

Depends on subtask 1 (pruned runtime; `delegated` token free to be re-bound).

## Validation Strategy

`(cd runtime-kotlin && ./gradlew check)`; targeted runs of the mode, pass-
sequence, legacy-contract, CLI-parsing, and telemetry-schema tests; one
manual CLI smoke: `--code-review-mode` with each token plus a duplicate-token
rejection.

## Next Path

Subtask 3 rewrites the governed content and docs for the new vocabulary.
