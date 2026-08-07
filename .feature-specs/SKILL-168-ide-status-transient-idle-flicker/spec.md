# SKILL-168 — A transient `no_matching_work` must not blank the IDE status widget

## Intended Outcome

While a goal is genuinely running, the IntelliJ status bar widget must not momentarily
collapse to `idle` / "No matching Skill Bill work for this repository." and then recover.
Observed live on 2026-08-07 during the SKILL-165 goal run: the widget tooltip read
`State: idle` / `Goal elapsed: —` / `Subtask elapsed: —` with that summary sentence, while
goal SKILL-165 was durably `running`.

Two independent defects combine to produce it, and this feature fixes both:

1. **The plugin has zero smoothing.** One bad sample erases a good live display outright.
2. **The runtime can produce that bad sample.** `IdeStatusService` collects candidates
   across dozens of statements with no consistent database snapshot, so a concurrent
   writer can tear the read and empty the candidate set.

Fixing (1) alone already removes the user-visible flicker, which is why it is the first
subtask and carries no dependency on (2).

## Evidence

**Mechanism (confirmed).** The CLI returned its `no_matching_work` problem snapshot for a
single poll — `lifecycle_state: idle`, `updated_at = observedAt`, `freshness: fresh`, exit
code 0, built at
`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/work/IdeStatusProblemSnapshots.kt:47-56`.
Every field in the observed tooltip follows from that snapshot.

**Amplifier (confirmed, plugin side).** Three seams, all present on `main`:

- `IdeStatusJsonMapper.kt:96-104` maps problem code `no_matching_work` to
  `SkillBillStatusOutcome.Idle`, deliberately treating it as a healthy idle repository
  rather than a failed read. The resulting `Idle` carries **no marker** distinguishing it
  from a lifecycle-derived idle.
- `LastKnownDisplayCache.kt:117-120` places `Idle` in the `-> null` group of
  `toCacheSnapshotOrNull`, so an `Idle` outcome neither writes nor reads the
  last-known-display cache.
- `StatusRefreshCoordinator.kt:103-112` falls back to the cached display only for
  `Unavailable` and `Incompatible`; every other outcome — `Idle` included — is emitted
  verbatim.

Net effect: a single glitchy sample replaces a live display with a blank one, and the next
good sample restores it. That is exactly the observed flicker.

**Ruled out as the cause of the bad sample.**

- The goal's repository binding is durably persisted
  (`goal_runner_controls.control_state_json.repository_identity =
  "repo-root-realpath-v1:/home/sermilion/StudioProjects/skill-bill"`), so the torn
  `childrenHere` / `childCountAnywhere` children-inference read in
  `IdeStatusService.matchesGoalRepository` is not reachable for a bound goal.
- `IdeStatusProjector.kt:275,291` reuse the same sentence as an `IDLE` summary, but those
  branches are unreachable here: `IdeStatusSelectionPolicy.lifecycleFromDurableState` never
  returns `IDLE`, so a selected candidate cannot project to it.
- `goal_issue_progress` for SKILL-165 was `status='running'` from 2026-08-07T12:05:21Z with
  `finished_at` NULL, so neither the "terminal row cannot be reopened" path nor the
  retention-ceiling drop path applies.
- 40 consecutive `skill-bill work status` samples during the live run all returned
  `feature-goal | active | fresh` with no problem code. This is a narrow transition window,
  not a steady-state error.

**Leading hypothesis for the bad sample (not yet reproduced).**
`IdeStatusService.collectCandidates` issues dozens of separate SELECTs on a read-only
connection opened *without* a transaction:
`runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/SQLiteDatabaseSessionFactory.kt:29-39`
invokes the block directly, unlike `transaction()` which uses `BEGIN IMMEDIATE` (`:49-57`).
With no cross-statement snapshot, a writer committing mid-collection yields a torn view that
can empty the candidate set and fall through to `IdeStatusService.kt:58-59`'s
`no_matching_work`.

## Acceptance Criteria

1. A `no_matching_work` response is distinguishable at the plugin's domain boundary from a
   lifecycle-derived idle, without changing the wire contract or the meaning of the
   `no_matching_work` code.
2. An isolated `no_matching_work` sample observed immediately after a live outcome does not
   change what the widget displays; the previously displayed state is retained.
3. A `no_matching_work` result that is corroborated by a subsequent consecutive
   `no_matching_work` sample does commit to the idle presentation, so a goal that genuinely
   finishes or is removed still settles to idle within a bounded number of polls.
4. A repository with no Skill Bill work at all still reads idle promptly, with no added
   delay on the first poll of a session and no held display from an unrelated prior state.
5. No held or cached display is ever presented as authoritative-active; the
   `LastKnownDisplayCache` contract that a cached display may surface only as `Stale` is
   preserved.
6. `IdeStatusService`'s candidate collection observes a single consistent database snapshot,
   so a concurrent writer commit cannot tear the read.
7. A live, correctly-bound, durably `running` goal is never reported as `no_matching_work`,
   regardless of concurrent write activity during candidate collection.
8. Existing ide-status behavior is otherwise unchanged: no wire or schema change, no
   `contract_version` bump, no change to selection retention windows or ordering, and the
   existing runtime and plugin test suites stay green.

## Constraints

- The plugin stays read-only over the `skill-bill work status` contract; no new CLI flags or
  commands.
- No ide-status wire/schema change and no `contract_version` bump. This is a presentation
  plus read-consistency fix.
- The plugin keeps depending only on `com.intellij.modules.platform`.
- **Sequencing: SKILL-168 must be implemented after SKILL-165 merges.** Subtask 1 touches
  `IdeStatusJsonMapper` and the plugin presentation seam, which SKILL-165 subtask 2 is
  editing concurrently (it adds a `Paused` outcome variant and `GoalPlanningInfo`, neither
  of which exists on `main`). Any smoothing policy that enumerates "live" outcome types must
  include `Paused` once SKILL-165 has landed.

## Non-Goals

- No change to the meaning of the `no_matching_work` problem code on the wire.
- No change to goal planning, checkpointing, or `planningStatus` computation.
- No change to `IdeStatusSelectionPolicy`'s retention windows or ordering.
- No new CLI commands or MCP tools.
- No change to the existing cold-start cache-fallback behavior for `Unavailable` /
  `Incompatible`.
- No plugin settings UI for tuning the corroboration threshold.

## Open Item

Subtask 2's *trigger* is hypothesis-stage: the torn-read mechanism is established by reading
the code, but the specific statement pair that produced the observed flicker has not been
captured. A bounded read-only sampler (2s interval, ~30 min, logging only anomalous samples
with raw JSON plus goal/child DB rows) was running at spec time to catch it. If that output
lands, use it to tighten subtask 2's reproduction criteria — but subtask 2's acceptance
criteria (6 and 7) are deliberately written to hold regardless of which pair tore, so the
fix is not blocked on reproducing it.

## Subtasks

1. Plugin transient-idle smoothing: distinguish `no_matching_work` at the domain boundary and
   require corroboration before the widget commits to idle. Covers AC 1–5.
2. Runtime read snapshot consistency: give `IdeStatusService` candidate collection a
   consistent snapshot. Covers AC 6–7. Ordered after subtask 1; no code dependency on it.
