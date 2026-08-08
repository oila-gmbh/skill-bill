# SKILL-172: Goal planning burst control and planning-context waste

Status: Prepared

## Intended Outcome

Goal planning stops generating the token burst that trips provider rate ceilings, and stops
spending its bounded context budget on content no planning agent can use. Concretely: a
14-subtask goal plans to completion in one run instead of blocking every two or three
subtasks and requiring a resume, and the shared context packet carries the curated boundary
memory it was designed to carry.

## Background

A goal with 14 subtasks blocked repeatedly during planning against `--agent cursor`. The
proximate symptom was Cursor exiting 0 with an empty harvest, but the investigation found two
skill-bill-side causes underneath it.

### Cause 1 — planning context is spent on review routing tables

`FileSystemGoalPlanningContextDiscovery.discover` populates three packet fields:

| Packet field | Reads |
|---|---|
| `platform_packs` | `platform-packs/*/platform.yaml` — every pack present |
| `boundary_memory` | `platform-packs/*/agent/history.md` + `agent/decisions.md` |
| `validation_guidance` | the repo's root `AGENTS.md` |

Discovery is bounded by `GoalPlanningContext.MAX_DISCOVERY_TOTAL_BYTES = 32KB` total,
`MAX_DISCOVERY_EXCERPT_BYTES = 4KB` per file, and it fills that budget in argument order —
platform packs first (`FileSystemGoalPlanningContextDiscovery.kt:17`), then boundary memory,
then AGENTS.md.

This repo has nine platform packs and every `platform.yaml` exceeds the 4KB excerpt cap. Eight
files at 4096 bytes exhausts the 32,768-byte budget exactly, at which point `canRead()` returns
false. So for every planning launch in a repo with eight or more packs:

- `platform_packs` — eight packs, 32KB, the full budget
- `boundary_memory` — **empty**
- `validation_guidance` — **empty**

The content being bought with that budget is not planning input. Every top-level key in
`platform.yaml` is review or quality-check routing: `routing_signals`,
`declared_code_review_areas`, `declared_files`, `area_metadata`, `lane_conditions`,
`declared_quality_check_file`, `pointers`. There is no build, convention, or architecture
guidance in it. Because of the 4KB excerpt cap the planner only ever receives the routing
header and a list of review-specialist content paths.

Meanwhile `boundary_memory` — the curated `history.md`/`decisions.md` handoff that makes
fresh-context-per-subtask planning work — is starved to nothing, and `AGENTS.md`, which is
genuinely repo conventions, never arrives either.

`GoalPlanningContext.platformPacks` has exactly one consumer: `GoalPlanningSweep.kt:684`,
which copies it into the packet. Nothing reads it downstream. The other `platformPacks`
symbols across install, scaffold and review are unrelated types operating on
`platformPacksRoot` paths.

### Cause 2 — the sweep is an unpaced burst source

`GoalPlanningContextPromptFormatter.append` serialises the whole shared packet into every
subtask plan prompt; only the tail varies per subtask (spec path and dependency metadata).
`producePlan` (`GoalPlanningSweep.kt:241`) calls `producePhase` once per subtask with no delay
between launches, so a 14-subtask goal fires 15 sequential launches of a large, near-identical
payload back to back.

Observed effect across 48 Cursor transcripts from one day: 11 empty turns, all clustered at
the end of runs of consecutive successful turns, each recovering after an idle gap. Planning
progress across resumes went 2 → 4 → 6 → 9 → 12 → 15, i.e. each resume cleared two or three
subtasks before hitting the ceiling again. A ~500-character control prompt carrying no packet
also returned empty inside a degraded window, so the trigger is cumulative account load, not
prompt content.

SKILL-172 does not attempt to fix the provider. It removes skill-bill's contribution to the
burst and stops the retry path from spending its whole budget inside the degraded window.

### Already landed

Commit `50bbdf38` on `fix/cursor-empty-planning-harvest` added `EmptyProviderTurn`
classification, bounded retry, durable rejection evidence under rule `empty-planning-harvest`,
and Cursor decoder corrections (camelCase usage keys, longest-assistant fallback,
`--stream-partial-output` split from liveness). That retry fires immediately with no delay,
which is what subtask 2 corrects.

## Acceptance Criteria

1. Goal planning discovery no longer reads `platform-packs/*/platform.yaml`; the
   `platform_packs` packet field serialises as an empty object.
2. In a repository with nine platform packs, a freshly built packet carries non-empty
   `boundary_memory` and non-empty `validation_guidance` — the regression that made both empty
   is covered by a test that fails against the current implementation.
3. The packet key set is unchanged, `GoalPlanningSharedContextPacket.VERSION` stays `"0.1"`,
   and a packet checkpointed before this change still passes `validate()` on resume.
4. Consecutive per-subtask plan launches are separated by a configurable pace interval, applied
   between launches only — never before the first launch and never after the last.
5. `EmptyProviderTurn` retries back off between attempts rather than relaunching immediately;
   the backoff grows per attempt.
6. Both the pace interval and the backoff schedule are injected through a seam a test can drive
   without real elapsed time, following the injected-`java.time.Clock` precedent already used in
   `GoalRunner` and `GoalRunnerExecutionCoordinator`. No bare `Thread.sleep` in application code.
7. Pacing and backoff are provider-neutral: no agent identity is read at either site.
8. Waiting is interruptible and honours the existing pause and authorization boundaries — a
   paced or backing-off sweep still stops promptly at a durable pause boundary rather than
   sleeping through it.
9. `./gradlew build -x sourcesJar` and `detekt` pass. (`:runtime-infra-fs:sourcesJar` fails on
   clean `main` for an unrelated task-dependency defect and is out of scope.)

## Non-Goals

- No removal of the `platform_packs` key or rename of `validation_guidance` to
  `repo_conventions`. Both change the packet key set, which `validate()` matches exactly and
  which is covered by `integrity_sha256`, so both require a `VERSION` bump and a recovery
  migration. Deliberately deferred to separate work.
- No change to fresh-context-per-subtask. Re-sending the shared packet per subtask is the
  design; this work reduces what the packet contains and how fast launches are issued, not the
  isolation model.
- No provider-side work: no Cursor bug report, no agent failover after N empty turns, no model
  override defaults for planning.
- No change to `MAX_FIX_LOOP_ITERATIONS` or to the retry cap itself.
- No change to the install, scaffold, or review subsystems that legitimately consume platform
  packs.

## Constraints

- `GoalPlanningSharedContextPacket.validate` asserts `packet.keys == PACKET_FIELDS` exactly and
  verifies `integrity_sha256` over the packet. Values may change freely; keys may not, without
  a version bump.
- Packets are checkpointed into the preplan payload and read back on resume. Any change must
  leave already-checkpointed packets recoverable.
- Pacing adds wall-clock time to a phase already bounded by `--planning-budget-minutes` and
  `--max-wall-clock-minutes`. The default pace must not push a normal goal past those bounds;
  state the arithmetic for a 15-subtask goal in the subtask spec.
- `allWarningsAsErrors = true` is in force.

## Dependencies

Builds on commit `50bbdf38` (`EmptyProviderTurn` classification and retry). Subtask 2 modifies
the retry path that commit introduced. Subtask 1 is independent of both.

## Next Path

After merge, re-run a wide decomposed goal against Cursor and compare planning completion in a
single run against the 2 → 4 → 6 → 9 → 12 → 15 resume pattern. If empties persist at the same
depth, the remaining burst is upstream of pacing and the deferred packet-key work plus provider
failover become the next candidates.
