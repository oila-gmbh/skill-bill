# SKILL-200 — Collapse the feature entry family into one entry-point skill

## Context

The feature-execution family carries 1035 lines of authored prose across four skills:
`bill-feature` (131), `bill-feature-goal` (425), `bill-feature-task` (57), and
`bill-feature-task-runtime` (422). The Kotlin runtime owns the phase loop, durable state, the
review contract, the remediation loops, and the schema gates. Almost none of that prose tells an
agent something the runtime does not already decide or already print.

Three separate problems produced it.

### A dead layer nothing reaches

SKILL-133 (unified manifest authority) made `skills/bill-feature/content.md` dispatch every
authoritative `decomposition-manifest.yaml` to `bill-feature-goal.md`, explicitly "including when
it contains exactly one subtask." That orphaned `bill-feature-task` and its downstream
`bill-feature-task-runtime` sidecar.

`bill-feature-task`'s only trigger is a `resumable` continuation-lookup result, which requires a
nonterminal row whose immutable identity carries `routeScope = STANDALONE`
(`FeatureTaskContinuationLookupService.kt:199`). Goal children are minted `GOAL_CHILD`
(`GoalRunnerWorkflowStores.kt:567`) and are filtered out of the standalone lookup, so they never
classify as resumable. The only producer of a STANDALONE row is `skill-bill feature-task run`
without the goal flags, whose only caller is `bill-feature-task-runtime`, reachable only through
`bill-feature-task`. The layer exists to resume rows only it can create, and cannot be reached to
create them. The repository owner has confirmed no standalone rows exist for any user.

Goal children never read either file: they are launched as a subprocess argv assembled at
`AgentRunCommandBuilders.kt:534`. Removing the router orphans the sidecar, so the pair is a closed
island of 479 lines.

### Prose that restates runtime internals

`bill-feature-goal` describes behavior no agent acts on: a worker model (28 lines), the child
review contract covering `review_base_sha` capture, pass reservation, and severity gating
(46 lines), planning-discovery caps and thin retention (21 lines), and a trailing findings-ledger
paragraph duplicating the review contract (3 lines). Its Status Checks section is 64 lines of
`--diff-stat` and `--diff-hunk` documentation with sample output that `--help` already owns.
Intake duplicates `bill-feature`'s intake (17 lines). Default output verbosity overlaps Watching
Long Runs (13 lines). The anti-polling and completion-signal block is triplicated across
`bill-feature-goal`, `bill-feature-task-runtime`, and partly `bill-feature`, and the
fresh-conversation handoff appears in all three.

### Instructions to reproduce output the CLI already emits

Two sections tell the agent to hand-compose text the runtime prints itself:

- Required: print the terminal monitoring command (24 lines) duplicates
  `GoalCliCommands.kt:951-954`, which already emits the monitor block with the real issue key
  substituted. Following the prose risks a second copy.
- Completion Signal (41 lines) has the agent compose `goal <key>: finished` and the
  `summary: … subtasks complete; … PR …` line from structured fields. `GoalCliCommands.kt:1077-1110`
  already renders exactly that, including the blocked, failed, and paused forms.

### Logic the agent hand-executes that the runtime already decides

- Prepare Spec (18 lines) makes the agent call `skill-bill feature-task lookup` and branch six ways
  across `no_match`, `resumable`, `already_running`, `ambiguous`, `terminal_only`, and
  `goal_continuation`. The runtime already decides these: `GoalRunner.kt:126` throws
  `GoalRunnerExecutionAlreadyRunningException`, `GoalRunner.kt:253` clears a requested pause on
  relaunch, and a missing manifest already loud-fails.
- Direct Dispatch (9 lines) is a manifest discovery and disambiguation algorithm over
  `.feature-specs/{ISSUE_KEY}-*/` written as rules the agent applies by hand.
- Agent add-on selection (27 lines) orchestrates a two-step CLI dance only because `goal` refuses
  raw tokens and demands pre-resolved JSON, while `skill-bill agent-addon resolve-selection`
  already resolves those tokens.
- Code-review selection appears in `bill-feature` (12 lines) and again in `bill-feature-goal`'s
  preamble, translating `code-review:<mode>` into a flag the CLI already validates.
- The Linear rehydrate section (29 lines) makes the agent reason about `spec_source`, incremental
  scratch deletion, and which specs are still needed. Only the MCP fetch itself must be agent-side.

### What genuinely earns its keep

Three responsibilities cannot move. The intake conversation that establishes the issue key and
outcome. The confirmation gate, because the runtime cannot ask the user anything. And the Linear
MCP fetch, which stays agent-side so the runtime keeps no Linear dependency. Everything else
belongs to the runtime or to `--help`.

## Intended Outcome

One skill. `bill-feature` gathers intake, runs one read-only preflight call, prints the gate block
the runtime composed, asks one question, fetches any spec the runtime says is missing, launches one
command, and relays the runtime's output verbatim. `bill-feature-task`,
`bill-feature-task-runtime`, and `bill-feature-goal` are gone. The runtime owns continuation
classification, manifest discovery, add-on token resolution, and gate rendering.

## Acceptance Criteria

1. `skills/bill-feature-task/`, `skills/bill-feature-task-runtime/`, and `skills/bill-feature-goal/` are absent from the repository, and `bill-feature` is the only skill in the feature entry family.
2. The durable `bill-feature-task` workflow identity is unchanged: the `DatabaseSchema.kt` CHECK constraint, the `orchestration/contracts/workflow-state-schema.yaml` `const`, the telemetry enum, and `FeatureTaskRuntimePhaseWorkflowDefinition`'s `skillName` and `workflowName`.
3. The `skill-bill feature-task run|resume|status|lookup|abandon|repair-identity` CLI is unchanged and still launches goal children.
4. No skill restates the runtime worker model, the child review contract, the remediation loops, review-base capture, pass accounting, severity gating, planning-discovery caps, or thin-retention rules.
5. No skill documents `--diff-stat`, `--diff-hunk`, or their sample output.
6. No skill instructs an agent to compose a monitor block or a terminal completion line; `bill-feature` relays runtime output verbatim and adds nothing.
7. The anti-polling rules and the fresh-conversation handoff each appear exactly once.
8. `skill-bill goal preflight <issue-key>` is read-only and returns one structured verdict covering new work, resumable continuation, already-running, ambiguous, terminal-only, and missing-manifest cases.
9. Preflight returns a ready-to-print confirmation-gate block covering issue key, feature name, ordered subtasks, the child agent including any override, the parallel-review lane, the resolved review mode marking an explicit `delegated` selection as experimental, and selected add-ons in caller order.
10. Preflight returns the set of spec files that must be rehydrated before launch, each with its issue key, `linear_issue_id`, and target path, and returns an empty set for `spec_source: local`.
11. `bill-feature` contains no continuation-lookup branch table, no manifest discovery or disambiguation algorithm, and no `spec_source` or scratch-deletion reasoning; it calls preflight once and acts on what it returns.
12. `skill-bill goal` accepts raw ordered agent add-on slugs and resolves them internally, rejecting malformed, duplicate, unknown, and unsupported values before any side effect. No skill constructs selection JSON or calls `agent-addon resolve-selection`.
13. Review-mode and parallel-review token handling is stated once, in `bill-feature`.
14. `bill-feature`'s prose covers only intake, preflight, the single confirmation gate, the Linear MCP fetch for preflight-listed targets, launch, and verbatim relay.
15. Preflight performs no durable write: it does not open a workflow, mutate manifest state, launch a child, clear a pause, or emit a lifecycle telemetry event.
16. A goal launched through the reduced surface reaches a terminal outcome with the same durable state, telemetry events, review behavior, and exit codes as before: `complete=0`, `failed=1`, `paused=2`, `blocked=3`.
17. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` all pass.
18. A clean `./install.sh` stages `bill-feature/` with the seven `kmp` Android add-ons and its governed support pointers, and with none of `bill-feature-task.md`, `bill-feature-task-runtime.md`, or `bill-feature-goal.md`.
19. Outside `.feature-specs/` and `agent/history.md`, no live source, contract, doc, or script references `bill-feature-task-runtime` or `bill-feature-goal`, and the only surviving references to `bill-feature-task` are the durable workflow identity and the CLI command name.

## Scope

Governed skill sources under `skills/bill-feature*`. The `feature-task` and
`feature-launch-warning` skill classes. The `kmp` pack add-on declarations. `WorkflowEngine.kt`'s
continuation content-path map. The goal CLI: a new read-only preflight verb, gate-block rendering,
rehydrate-target reporting, and raw add-on slug resolution. `install.sh`'s closing hint,
`runtime-kotlin/ARCHITECTURE.md`, and the three documentation files that map the feature family.
Tests that stage or assert the feature family.

## Constraints

The durable workflow identity is load-bearing and stays. Renaming persisted state to delete prose
would be the wrong trade.

Preflight must be read-only. It is the input to a confirmation gate, so any side effect would fire
before the user has agreed to anything.

Behavior visible to a user must not change. Exit codes, telemetry events, review severity gating,
and durable state stay exactly as they are. This work moves where decisions are written down, not
what they decide.

The runtime gains no Linear dependency. Preflight names which spec files are missing; the MCP fetch
stays agent-side.

Prose deletion and runtime-ownership changes land in separate commits, so a bisect can tell a
markdown edit from a behavior change.

A live SKILL-190 goal was mid-review when this spec was prepared. Do not begin implementation until
that goal is terminal or paused, because writing to `skills/` lands inside the SKILL-190 subtask's
review delta.

## Non-Goals

Changing the phase loop, the review contract, the remediation loops, the durable schemas, or any
telemetry event shape.

Reintroducing a standalone single-spec execution path. A single-subtask goal is a goal, and every
goal rule applies to it.

Moving the intake conversation, the confirmation gate, or the Linear MCP fetch into the runtime.

Trimming `bill-feature-spec`, `bill-feature-verify`, or any skill outside the feature entry path.

## Subtasks

1. Remove the two dead skill trees and repoint the governed surfaces that named them.
2. Remove the dead continuation content-path entries and correct the runtime, install, and
   documentation prose describing the deleted skills.
3. Delete the prose no agent acts on: runtime-internals restatement, the CLI flag catalogue, the
   triplicated blocks, and the two sections that reproduce output the CLI already prints.
4. Give the runtime the decisions the agent hand-executes: a read-only `goal preflight` verb
   returning verdict, gate block, and rehydrate targets, plus raw add-on slug resolution.
5. Collapse the family into `bill-feature` and delete `skills/bill-feature-goal/`.

## Validation Strategy

Subtasks 1 through 3 are prose and declaration changes verified by `skill-bill validate`, a clean
`./install.sh` checked against criterion 18, and the Kotlin suites covering install staging and
feature-family rendering. Expect governed-content and rendering tests to fail first, because they
assert the presence of what is being deleted.

Subtask 4 is a behavior change verified by `(cd runtime-kotlin && ./gradlew check)` with coverage
for each preflight verdict, gate-block composition, rehydrate-target reporting under both
`spec_source` values, and raw add-on slug resolution including rejection paths. Criterion 15 needs a
test asserting no durable write, no workflow open, no pause clear, and no lifecycle telemetry event.

Subtask 5 is verified end to end: launch a real goal through the single reduced skill and confirm
criterion 16 against durable state, telemetry, and exit code.

Every subtask finishes with `npx --yes agnix --strict .` and `scripts/validate_agent_configs`.

## Next Path

```bash
skill-bill goal SKILL-200
```
