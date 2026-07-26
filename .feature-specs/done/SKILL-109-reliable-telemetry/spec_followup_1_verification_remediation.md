---
issue_key: SKILL-109
status: Complete
agent: claude
execution_mode: standalone
source: PR 214 feature verification, review run rvw-20260709-195116-eta1
---

# SKILL-109 Follow-up 1 - Telemetry reliability verification remediation

## Intent

Close the correctness, idempotency, production-trigger, scalability, normalization, and test-quality
gaps found while verifying PR 214. This is a standalone follow-up spec in the SKILL-109 feature
folder. It is not a seventh decomposition subtask and must not be added to
`decomposition-manifest.yaml`.

## Problem

The first SKILL-109 implementation adds the requested telemetry surfaces, but several paths still
make the resulting metrics unreliable:

- stale goal reconciliation can abandon issues whose last segment was not blocked;
- stale-late and repeated ordinary finishes can emit more than one terminal event per session;
- replayed goal-segment writes can inflate issue aggregates;
- a terminal issue write without a progress row can emit blank or false aggregates;
- preflight policy-blocked goal invocations emit no goal lifecycle telemetry;
- reconciliation is reached from MCP auto-sync but not from CLI-only execution or manual CLI sync;
- reconciliation performs unindexed full-history scans and processes an unbounded backlog in one
  SQLite write transaction;
- ambiguous KMP/Kotlin labels can normalize to Kotlin;
- reliability tests validate hand-authored maps or encode behavior opposite to the intended
  contract.

## Scope

### Goal issue correctness

- Restrict abandonment to issues with a non-null last blocked timestamp whose blocked segment is
  still the latest issue activity. Never abandon a started-only issue or an issue with activity
  after its last blocked segment.
- Make issue aggregate updates idempotent by segment identity. Replaying `goalStarted` or
  `goalFinished` for an existing segment must not increment invocation, resume, or block totals.
- When issue completion is observed without a `goal_issue_progress` row, recover trustworthy
  aggregate data from persisted segment history before emission. If required history cannot be
  recovered, fail loudly or suppress the invalid terminal event rather than emitting blank
  `first_started_at` or fabricated zero totals.
- Emit started and finished telemetry for preflight policy-blocked goal invocations, including the
  `POLICY_BLOCKED` stop reason and the stable parent workflow id.

### Exactly-once terminal lifecycle

- Enforce at most one terminal `finished` event per lifecycle session across feature-task prose,
  feature-task runtime, feature verify, and quality check.
- A normal finish arriving after stale reconciliation may update diagnostic/local state, including
  the duplicate counter, but must not append another terminal event. If an unsynced stale outbox
  record is corrected, replacement must be atomic and must still leave exactly one terminal outbox
  event.
- Repeating an ordinary non-stale finish must be idempotent and must not append a second event.

### Production reconciliation trigger and bounds

- Invoke reconciliation from a shared production boundary that covers both MCP and CLI-only
  sessions. Manual CLI telemetry sync must reconcile before flushing; a documented startup or
  periodic trigger may additionally be used.
- Decouple reconciliation cadence from every lifecycle event. Throttle it independently from
  telemetry upload sync.
- Add indexes supporting unfinished-session and abandoned-goal candidate selection.
- Process a bounded batch per transaction so a historical backlog cannot monopolize the SQLite
  write lock or allocate an unbounded in-memory session list.

### Label normalization

- Normalize explicit KMP/multiplatform labels to `kmp` even when the same input also contains the
  token `kotlin`.
- Prefer a manifest-derived routed platform slug when it is available and preserve removed prose
  only in the descriptive detail field.

### Regression coverage

- Replace hand-authored reliability payload maps with payloads emitted through real lifecycle
  services/stores for the guarantees under test.
- Rewrite stale reconciliation tests so their names and assertions enforce the same exactly-once
  and last-segment-blocked contracts.
- Add negative and replay tests for every failure mode listed in this spec.

## Acceptance Criteria

1. A never-blocked goal issue is not marked or emitted as `abandoned`, regardless of age.
2. A goal issue with activity newer than its last blocked segment is not marked or emitted as
   `abandoned`.
3. A goal issue whose latest activity is a blocked segment and is older than the configured
   threshold emits exactly one `goal_issue_finished` event with `status = abandoned`.
4. Replaying `goalStarted` for the same segment workflow id does not change `total_invocations` or
   `total_resumes`.
5. Replaying a blocked `goalFinished` for the same segment does not change `total_blocks`.
6. Completing a goal without a pre-existing progress row either recovers accurate non-blank
   aggregates from persisted segment history or fails/suppresses emission loudly; it never emits a
   terminal issue event with blank `first_started_at` or fabricated zero history.
7. A preflight policy-blocked goal invocation emits one `goal_started` and one `goal_finished`
   event, with `stop_reason = POLICY_BLOCKED`, and contributes once to issue aggregates.
8. Each feature-task prose, feature-task runtime, feature-verify, and quality-check session emits at
   most one terminal finished event when a normal finish arrives after stale reconciliation.
9. Repeating an ordinary non-stale finish for each lifecycle family does not enqueue another
   terminal event; duplicate diagnostics may increment without changing terminal event count.
10. Reconciliation runs for CLI-only sessions through a documented production trigger, including
    before manual `skill-bill telemetry sync` flushes pending events.
11. Reconciliation has an independent cadence guard, indexed candidate queries, and a configured
    maximum batch size per transaction.
12. A backlog larger than the batch size is drained across repeated reconciliation runs without
    duplicate events, lost candidates, or an unbounded transaction.
13. `KMP/Kotlin`, `Kotlin Multiplatform (KMP)`, and equivalent mixed labels normalize to `kmp`, while
    ordinary Kotlin labels still normalize to `kotlin`.
14. Manifest-derived platform routing takes precedence over ambiguous free-text detection, and any
    removed description remains available only through the detail field.
15. Reliability contract tests obtain representative payloads from real emitters/outbox rows and
    fail when terminal completeness, aggregate accuracy, routing normalization, duration, enum, or
    blocked-reason guarantees are deliberately broken.
16. Stale-reconciliation tests assert one terminal event after a late normal finish and assert that
    started-only or newer-active issues remain non-terminal.
17. Replay tests cover duplicate goal start, duplicate blocked goal finish, duplicate ordinary
    lifecycle finish, and repeated reconciliation.
18. `(cd runtime-kotlin && ./gradlew check)`, `skill-bill validate`,
    `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` all pass.

## Technical Constraints

- Preserve the additive, migration-safe SQLite model; do not rewrite existing telemetry history.
- Keep `goal_issue_finished` runtime-internal rather than adding an MCP lifecycle tool.
- Keep reconciliation best-effort with respect to product workflows: reconciliation failure may be
  captured diagnostically but must not fail the feature or goal operation that triggered it.
- Preserve telemetry-off behavior and configured privacy levels.
- Keep SQL identifiers static or validated through the existing safe-identifier boundary.
- Do not solve duplicate terminal events by silently discarding the duplicate diagnostic counter;
  event idempotency and diagnostic accounting are separate requirements.

## Non-goals

- Redesigning the telemetry transport, proxy, retention policy, or PostHog dashboards.
- Solving general SQLite `SQLITE_BUSY` handling outside the reconciliation queries introduced by
  SKILL-109.
- Redesigning goal selection, dependency blocking, or feature-task workflow semantics.
- Adding this follow-up to the completed SKILL-109 decomposition manifest.
- Modifying the separate SKILL-110, SKILL-111, or SKILL-112 feature-spec packages.

## Validation Strategy

- Persistence integration tests use a real temporary SQLite database and inspect both session rows
  and telemetry outbox event counts.
- Goal tests exercise a never-blocked issue, an old block followed by newer activity, a truly
  abandoned last-blocked issue, segment replay, legacy/missing progress, and preflight policy block.
- Lifecycle tests exercise stale-late and ordinary duplicate finishes for all four lifecycle
  families and assert exactly one terminal outbox event.
- Reconciliation tests seed more candidates than the configured batch size and verify deterministic
  multi-run draining.
- Normalization tests cover mixed KMP/Kotlin labels, plain Kotlin, fallback narration, unknown
  values, and manifest-derived routing precedence.
- The reliability contract test validates payloads actually produced by the implementation and
  includes a mutation-style negative assertion for every guarantee it claims to lock down.

## Completion Evidence

- Record the final PR or commit reference.
- Include the focused regression-test commands and the full validation command results.
- Re-run `bill-feature-verify` against this standalone spec and PR 214 (or its remediation branch)
  before marking the follow-up complete.
