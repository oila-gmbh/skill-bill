---
status: Ready for implementation
issue_key: SKILL-121
source: inline user request
---

# SKILL-121: Reuse prepared context across delegated code-review specialists

## Intended Outcome

Delegated code review prepares repository and diff context once in its parent
orchestrator, then gives each selected specialist a compact, lane-specific
brief. Specialists spend their context on evidence for their assigned changed
files rather than independently rediscovering scope, stack routing, project
guidance, build topology, and unrelated repository history.

The change must preserve independent specialist reasoning, exact review scope,
governed rubric ownership, evidence requirements, and the existing loud-fail
behaviour. It is an efficiency and focus improvement, not a reduction in the
review standard.

## Background

A single delegated review can start many specialist workers. The existing
contract asks the parent to pass changed files, guidance, and rendered
instructions, but does not require a preparation phase or a consistent brief.
The Codex parallel-lane prompt also directs workers to spawn every area declared
by the platform pack, even when the pack's routing rules would select only a
subset. The CLI parallel runner compounds the duplication by embedding every
specialist rubric into each lane prompt.

Those paths cause every worker to repeat broad repository discovery and inflate
token consumption without improving coverage of the changed code.

## Decided Behaviour

Before launching a routed review layer or specialist worker, its parent creates
one in-memory shared review-context packet. The packet contains only verified,
review-relevant facts:

- the exact resolved scope, diff source, changed-file/hunk map, and immutable
  session/run identifiers;
- routed stack, selected add-ons, ordered selected lanes, and the reason a lane
  is in or out of scope;
- applicable project guidance and only the repository/build/test facts needed
  to assess the selected change; and
- a lane assignment for every worker: its sidecar/rubric, owned changed files
  and hunks, relevant direct dependencies, and any explicit evidence to verify.

The parent passes the packet plus the worker's assignment to every worker. A
worker treats those facts as authoritative: it does not repeat scope/stack/
guidance discovery, scan unrelated files, or inspect the full repository. It
may inspect its assigned hunks and directly referenced dependencies when that is
needed to prove a concrete finding. The packet remains factual and compact; it
must not copy a repository dump, full project documentation, or every rubric
into every worker prompt.

Specialist selection remains manifest and pack driven. A parent launches only
the lanes selected by the routed pack's diff-signal rules (including required
baseline lanes), not every area declared in the manifest. Empty or irrelevant
lanes stay out of the run.

The CLI two-lane runner passes the pre-resolved stack and exact diff to each
lane parent. It must not serialize all specialist rubrics into both lane
prompts. Each lane parent follows the same shared-context preparation contract
and selects its own relevant workers. Parallel review remains an explicit
second full lane; this feature does not change its confirmation, timeout,
merge, or provenance behaviour.

Only the first, normal Step 5 review may use delegated mode. It receives the
feature's selected review mode and remains the sole pass allowed to launch
delegated specialist workers. Every later review pass, including a review-fix
re-review and an audit-gap re-entry, explicitly requests
`mode:inline`. The established inline-eligibility safety gate remains
in force. If a re-review is ineligible for inline review, the workflow stops
with that explicit reason; it must not silently substitute a delegated review.

Each subtask has a durable two-pass review budget. Pass one is the initial
normal Step 5 review. Pass two, when needed, is the only inline re-review and
may be consumed by either a review-fix loop or an audit-fix loop. An audit-fix
loop can launch at most that one inline code review. Once two passes have run,
the workflow must not start another code review for that subtask, regardless of
which loop asks for it; it records the exhausted review budget and continues or
stops according to the owning audit/validation contract without substituting a
third delegated or inline review.

## Scope

- `orchestration/review-delegation/PLAYBOOK.md` and the code-review shell
  contract define the parent preparation phase, packet contents, worker
  boundaries, and hand-off requirements.
- `skills/bill-code-review/content.md` updates the Codex lane-2 instructions
  to prepare the packet once and launch only routed selected areas.
- `ParallelCodeReviewRunner` stops loading and embedding every specialist
  rubric in each of its two lane prompts and instead instructs each lane parent
  to use the shared-context contract against the exact diff.
- Feature-task durable review-pass state distinguishes the initial Step 5
  review from re-reviews and caps every subtask at two total passes. Only the
  initial pass may carry the selected delegated mode; review-fix and audit-gap
  re-entries share the one remaining inline pass and preserve an
  ineligible-inline failure rather than widening back to delegated workers.
- The now-unused rubric-loading adapter/port is removed with focused runtime
  tests that protect the compact parent prompt and pre-resolved-stack hand-off.
- Source changes are rendered/installed through the normal staging flow; no
  generated skill wrappers or support pointers are committed.

## Non-Goals

- Eliminating independent specialist context windows or collapsing delegated
  review into a single generic pass.
- Changing platform-manifest schema, specialist rubrics, severity rules,
  telemetry ownership, or review-result merge semantics.
- Disabling explicit two-lane parallel review or changing its timeout policy.
- Persisting review packets as a new durable workflow artifact.

## Acceptance Criteria

1. Before delegated routed layers or specialist workers launch, the parent
   prepares one shared review-context packet containing the resolved scope,
   routing decision, applicable project guidance, relevant build/test facts,
   file/hunk map, selected add-ons, selected lanes, and per-worker assignment.
2. Every delegated worker receives that packet and its lane assignment. It is
   instructed not to repeat repository, scope, stack, or guidance discovery;
   it may read only assigned changed code and direct dependencies needed to
   verify a reachable finding.
3. The packet is compact and factual: it does not duplicate full repository
   documentation, unrelated diffs, or all specialist rubrics for every worker.
4. The normal routed review and Codex parallel lane select only the areas
   chosen by pack diff-signal routing, plus required baseline layers. They do
   not fan out to every `declared_code_review_areas` entry.
5. The CLI parallel runner's lane prompt preserves the exact supplied/resolved
   diff and detected stack but no longer loads or embeds all specialist rubric
   bodies. It directs the lane parent to use the shared packet and normal
   routed specialist selection without recursive parallel launch.
6. The obsolete rubric prompt-loading port, adapter, model, and dependency
   binding are removed; no production reference to the removed API remains.
7. Focused tests prove both lanes receive the exact diff and pre-resolved stack,
   the compact-parent instruction is present, flattened specialist rubric text
   is absent, and existing stack-detection, supplied-diff, failure, timeout,
   and merge behaviour remains intact.
8. The initial normal Step 5 review is the only feature-task review pass that
   may use the selected delegated mode. Its existing mode-selection and
   delegated-worker failure behaviour remains unchanged.
9. Every later review pass, including review-fix and audit-gap re-entry,
   invokes `bill-code-review mode:inline`. It neither launches
   delegated specialist workers nor silently replaces the request with
   delegated mode; an ineligible inline re-review fails with the existing
   explicit eligibility reason. Durable resume state preserves which review
   pass is initial versus a re-review.
10. Each subtask executes at most two total code-review passes. The first is
    the initial Step 5 pass; a review-fix or audit-fix loop may consume only
    the single remaining inline pass. When that budget is exhausted, neither
    loop can launch a third review, delegated or inline. Focused tests cover
    audit-fix consuming the remaining pass, review-fix consuming it first, and
    durable resume retaining the exhausted budget.
11. `skill-bill validate`, the relevant Gradle test suite, `npx --yes agnix
   --strict .`, and `scripts/validate_agent_configs` pass. `./install.sh` runs
   after source-contract changes so local staged installations refresh.

## Validation Strategy

Run the focused `ParallelCodeReviewRunnerTest` class while iterating, then the
full maintainer validation gate. Render `bill-code-review` to confirm the
installed shell exposes the revised shared delegation contract and inspect the
generated staging hash after `./install.sh`.
