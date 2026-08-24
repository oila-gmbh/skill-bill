# SKILL-200 Subtask 4 — Runtime-owned preflight and add-on resolution

## Intended Outcome

The runtime owns every pre-launch decision an agent currently makes by hand. One read-only
`skill-bill goal preflight <issue-key>` call returns what to do, what to show the user, and what
must be fetched first. `skill-bill goal` accepts raw add-on slugs. After this subtask the entry
skill has nothing left to branch on.

## Scope

Add `skill-bill goal preflight <issue-key>` as a read-only command with `--format json` support,
returning:

- **verdict** — one value covering the cases the agent currently branches across: new work needing
  spec preparation, resumable continuation, already running, ambiguous candidates, terminal only,
  and missing or invalid manifest. Reuse the existing classification in
  `FeatureTaskContinuationLookupService` and the goal-continuation lookup rather than writing a
  second classifier. Ambiguous results carry every candidate; they must not be resolved by
  recency.
- **gate_block** — ready-to-print confirmation text composed by the runtime: issue key, feature
  name, ordered subtasks with dependency notes, the expected first runnable subtask, the child
  agent including any override, the parallel-review lane or `none`, the resolved review mode
  showing `inline (default)` when omitted and marking an explicit `delegated` selection as
  experimental, and selected add-on slugs with manifest descriptions in caller order.
- **rehydrate_targets** — the spec files that must exist before launch but do not, each with
  issue key, `linear_issue_id`, and target path. Empty for `spec_source: local`. This moves the
  `spec_source` reading, the incremental-scratch-deletion reasoning, and the still-needed-versus-
  already-consumed distinction out of prose. Linear-mode goals delete each subtask's spec scratch
  on success, so an earlier subtask's absent spec is healthy and must not be listed.

Extend `skill-bill goal` to accept repeatable raw ordered agent add-on slugs, resolving them
internally through the existing `agent-addon resolve-selection` path. Reject malformed, empty,
duplicate, unknown, unsupported-consumer, and agent-incompatible values before any workflow,
branch, or phase side effect, preserving caller order. Keep the resolved structured selection as
the internal representation so verification of source identity and content digest is unchanged.

Preflight must not write. No workflow open, no manifest mutation, no child launch, no pause clear,
no lifecycle telemetry event.

## Acceptance Criteria

1. `skill-bill goal preflight <issue-key>` exists, supports `--format json`, and is documented in its own `--help` output.
2. Preflight returns exactly one verdict per invocation, covering new work, resumable continuation, already running, ambiguous, terminal only, and missing or invalid manifest.
3. An ambiguous result carries every candidate and never selects one by recency.
4. A malformed request, identity or snapshot error, selector mismatch, or invalid manifest produces a loud typed failure rather than degrading into the new-work verdict.
5. Preflight reuses the existing continuation and goal-continuation classification rather than introducing a second classifier that can drift from what launch does.
6. Preflight returns a `gate_block` containing issue key, feature name, ordered subtasks with dependency notes, expected first runnable subtask, child agent including any override, parallel-review lane or `none`, resolved review mode with `inline (default)` shown when omitted and explicit `delegated` marked experimental, and add-on slugs with descriptions in caller order.
7. Preflight returns `rehydrate_targets` listing every spec file needed before launch that is absent, each with issue key, `linear_issue_id`, and target path.
8. `rehydrate_targets` is empty when `spec_source` is `local` or omitted, and no Linear call is made on that path.
9. For `spec_source: linear`, a spec whose subtask is already complete and whose scratch was deleted on success is not listed as a rehydrate target.
10. Preflight performs no durable write: a test asserts no workflow row is opened, no manifest state mutates, no child launches, no requested pause is cleared, and no lifecycle telemetry event is emitted.
11. `skill-bill goal` accepts repeatable raw ordered agent add-on slugs and resolves them internally, preserving caller order.
12. Raw add-on slugs that are empty, malformed, duplicated, unknown, declared for an unsupported consumer, or incompatible with the resolved agents are rejected before any workflow, branch, or phase side effect.
13. Add-on source identity and exact-byte content-digest verification before prompt injection is unchanged.
14. The runtime gains no Linear dependency: no Linear client, MCP call, or Linear-specific network path is added.
15. Launch behavior is unchanged for every existing invocation: durable state, telemetry events, review severity gating, and the exit codes `complete=0`, `failed=1`, `paused=2`, `blocked=3`.
16. `(cd runtime-kotlin && ./gradlew check)` passes, with coverage for each verdict, gate-block composition, both `spec_source` rehydrate paths, and each add-on rejection reason.
17. `skill-bill validate`, `npx --yes agnix --strict .`, and `../../../scripts/validate_agent_configs` pass.

## Non-Goals

Editing any skill prose. Subtask 5 consumes this surface; this subtask only builds it.

Changing `skill-bill feature-task` subcommands, the phase loop, the review contract, or any
durable schema.

Making preflight able to launch, resume, reset, or repair anything. It reports; the existing
commands act.

Moving the Linear MCP fetch into the runtime. Preflight names the missing files and stops there.

## Dependency Notes

Depends on subtask 1 for a coherent starting state: the standalone `resumable` path it classifies
is the one whose consuming prose subtask 1 removes, and preflight should not be designed around a
dispatch target that is being deleted.

Independent of subtask 3, which is markdown only.

Subtask 5 depends on this. It cannot delete the agent's branch table until preflight answers the
same questions.

## Validation Strategy

Drive each verdict from a constructed workflow-state fixture rather than a live run, so all six
are reachable deterministically. Assert `gate_block` content field by field, including the
`inline (default)` rendering and the experimental marking on `delegated`, because criterion 6 is
what lets the prose shrink to print-and-ask.

Criterion 10 is the one that protects the user: assert absence of writes, not just presence of
output. A preflight that opens a workflow would mint state before the confirmation gate.

Cover both `spec_source` values for `rehydrate_targets`, including the completed-subtask case in
criterion 9, which is the easy bug: listing a spec that was correctly deleted on success would
send the agent to fetch something it must not restore.

For add-on resolution, one test per rejection reason and one asserting caller order survives
resolution.

Close with a full goal run to confirm criterion 15.

## Next Path

Proceed to subtask 5.
