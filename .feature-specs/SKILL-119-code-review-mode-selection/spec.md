---
status: Ready for implementation
issue_key: SKILL-119
source: inline user request
---

# SKILL-119: Select Code-Review Execution Mode from `bill-feature`

## Outcome

Feature runs can explicitly select how their code-review phase executes:

```text
/bill-feature SKILL-119 code-review:inline
/bill-feature SKILL-119 code-review:delegated
```

When the argument is absent, the current automatic selection remains the
default. The selection applies consistently to single-spec and decomposed
runs, in both runtime and prose execution modes, including review fix loops
and resumed durable workflows.

For every review in a decomposed goal, the goal-facing progress output shows a
compact finding summary keyed by class or symbol name, never a full file path.
Each subtask receives at most two total review passes. Once that cap is
reached, the goal records any unresolved findings and continues instead of
blocking on review.

The feature preserves the governed safety boundary outside this explicit
goal-only continuation rule: an inline request never bypasses the existing
inline-eligibility rules.

## Background

`bill-code-review` already has an execution-mode contract: inline review is
allowed only for a small, low-risk, single-layer scope; otherwise review must
be delegated to the selected specialist workers. `bill-feature` currently
does not expose that decision. It only passes feature execution mode
(`runtime` or `prose`) and an optional second full review lane
(`parallel-review:<agent>`).

As a result, a feature author cannot request delegated review for a small
change, cannot make an eligible inline review explicit, and cannot see the
choice at the feature confirmation boundary. A string added only to the top
level skill would not be sufficient: the request crosses authored feature
routers, the prose orchestrator, the feature-task runtime CLI and phase prompt,
and the decomposed-goal child launch path.

The normal review loop is also optimized for a single feature task: it may
block on unresolved Blocker or Major findings after its three-iteration cap.
That behavior can halt a long decomposed goal indefinitely and currently
surfaces verbose child review output. Goals need a deliberate, durable policy
for bounded review effort and concise operator-visible reporting without
damaging the full review record used by telemetry, remediation, or debugging.

## Decided Behavior

### Public feature argument

`bill-feature` accepts one optional lower-case argument:

```text
code-review:auto|inline|delegated
```

`code-review:auto` and an absent argument are equivalent. The default remains
automatic selection, preserving every existing invocation that does not opt
in. `code-review:inline` and `code-review:delegated` are the two explicit
selections requested by the user.

The argument is distinct from:

- `mode:runtime|prose`, which selects how the feature workflow itself runs;
  and
- `parallel-review:<agent>`, which asks for a second, independent full review
  lane.

Malformed, unknown, duplicate, or conflicting `code-review:` arguments must
fail loudly before the feature confirmation gate or runtime launch. Do not
silently treat them as `auto`.

### Selection semantics

- **auto**: retain the current `bill-code-review` selection contract exactly.
- **delegated**: require delegated execution even when the diff would be
  inline-eligible. The normal routed baseline/specialist set, add-ons,
  learnings, telemetry, aggregation, finding deduplication, and verdict
  contracts still apply.
- **inline**: run inline only after the existing eligibility check succeeds.
  If the scope exceeds the file/line threshold, needs multiple routed layers,
  has a high-risk signal, or is otherwise ineligible or unclear, stop before
  review with an actionable reason that delegated review is required. Do not
  silently upgrade, downgrade, or omit a review.

Explicit delegated review remains subject to the documented runtime capability
contract. If the active runtime cannot start the required delegated workers,
the run must block loudly rather than collapsing the work into an inline
review.

### Interaction with parallel review

`parallel-review:<agent>` continues to mean a second full review lane and is
not renamed or repurposed. It may be combined with the new selection. The
selected code-review mode must be carried into the primary review and every
parallel lane that performs a routed review; lane two must not recursively
start another parallel review. The final merged report continues to use the
existing provenance and telemetry format.

For goal review budgeting, all concurrently run lanes together are one review
pass. A two-lane review does not consume two of the two permitted passes.

### Goal subtask review scope

At the start of each decomposed-goal subtask, record the exact checked-out
commit as that subtask's `review_base_sha` before implementation mutates the
worktree. Every review pass for that subtask compares the current subtask
worktree against this durable base, including that subtask's staged, unstaged,
and owned untracked changes.

This means a re-review examines the complete current delta for the subtask,
not only the most recent repair diff. It can therefore find an earlier
subtask-local defect that the first pass missed. It must never widen the scope
to `origin/main...HEAD`, the full feature branch, or changes committed by
earlier subtasks. The next subtask captures the commit produced by its
predecessor as its own baseline, so only its new changes are reviewed.

Persist and reuse `review_base_sha` on every retry, review-fix loop,
audit-driven re-entry, and resume. If the stored commit is missing or no longer
an ancestor/reachable baseline for the selected subtask worktree, block loudly
instead of silently substituting `origin/main`, `HEAD`, or a branch-wide diff.

### Goal review presentation

For each code-review pass in a decomposed goal, emit one compact goal-facing
summary. It includes the subtask id, pass number, verdict or continuation
state, finding count, and each meaningful finding's severity, class/symbol
label, and concise description. For example:

```text
goal review: subtask=2 pass=1 changes_requested findings=2
  Major FeatureTaskRuntimeRunner — re-entry loses the selected review mode
  Minor GoalRunCommand — child review preference is not forwarded
```

The presentation must not include absolute or repository-relative file paths,
line numbers, diff hunks, or raw child-review text. Derive the label from the
finding's reported class or symbol. When no class/symbol is available, use a
sanitized file stem as the label; never fall back to a path. Group or
deduplicate repeated findings for the same class/symbol within the compact
summary.

This is a goal-facing rendering rule only. The complete routed review report,
including exact locations, remains intact in durable `review_result` artifacts,
the review/telemetry import path, and explicit diagnostic storage. Do not
weaken the machine-readable Risk Register or evidence needed for fixes.

### Goal-specific review cap and continuation

Each decomposed goal subtask has a maximum of **two total code-review passes**
over its whole lifecycle: the initial review and, at most, one re-review after
fixes. This is a total-pass cap, not two additional fix cycles. It includes
review passes reached through review-fix or audit-gap re-entry; a resume never
resets the count. Parallel lanes together count as one pass.

Before the cap, the existing finding-fix behavior applies. At the second pass,
or when a resuming subtask has already used its two passes, persist the complete
review result and the durable count. If unresolved Blocker or Major findings
remain, record an explicit `review_cap_reached` continuation disposition with
the unresolved findings and pass count, emit the compact summary, and advance
the goal to its next normal phase. Do not falsely mark the review as approved,
discard findings, or ask the user to choose fixes.

Later audit-driven changes in that subtask do not trigger a third review pass.
They carry the persisted review-cap disposition forward so the goal can
continue deterministically. Other gates, including audit, validation,
commit/push, dependencies, and terminal reporting, retain their own behavior.
The final goal outcome and per-subtask summary must make the capped review and
unresolved finding count visible.

This continuation policy applies only to decomposed goal child runs, in both
runtime and prose goal modes. Standalone/single-spec feature tasks retain the
existing review loop cap and blocking behavior.

### Handoff and durability

The selected review mode must appear in the existing single confirmation gate
along with feature execution mode and any parallel-review agent. The value is
then forwarded without reinterpretation through:

1. `bill-feature` to either the single-task or decomposed-goal route;
2. `bill-feature-task` to prose or runtime execution;
3. the runtime CLI request, application request, and review-phase prompt; and
4. the runtime and prose goal child-task paths.

Persist the resolved selection, `review_base_sha`, and goal review-pass/
disposition state in the existing durable workflow/goal state before review can
run. A review-fix loop, an audit-driven re-review, or a resume without a new
selection must reuse that stored state. An explicit value on a resume may not
silently change a review already in progress; reject an incompatible change
loudly or retain the stored value with an explicit, documented precedence rule.
Keep durable workflow records schema-valid and do not weaken their loud-fail
behavior.

`bill-code-review` receives the canonical execution-mode request through its
own documented argument (for example `execution-mode:auto|inline|delegated`),
so standalone code review and feature-owned review share one selection and
eligibility implementation instead of duplicating the policy in feature
skills.

## Scope

### 1. Governed review contract

Extend the `bill-code-review` argument contract to accept the canonical
execution-mode request, retain automatic behavior by default, and report the
selected/effective mode in its normal summary. Apply the existing shared
execution-mode and delegation contracts as the single source of safety rules;
do not duplicate thresholds or risk classification in a feature skill.

### 2. Feature authored routes

Teach `bill-feature`, `bill-feature-task`, `bill-feature-task-runtime`,
`bill-feature-task-prose`, and `bill-feature-goal` to parse, show, and forward
the feature-facing `code-review:` argument. The prose Step 5 invocation must
pass the canonical request to `bill-code-review`; it must not add another
wrapper subagent around that skill.

For decomposed prose goals, include the resolved selection, `review_base_sha`,
current review-pass count, cap disposition, exact subtask-delta review scope,
and compact-summary rendering rules in each self-contained subtask briefing/
continuation context so every subtask observes the same policy. Preserve the
current one-confirmation-gate rule.

### 3. Runtime and goal propagation

Add a typed runtime representation of the selection, a validated
`skill-bill feature-task` option, and the corresponding goal-run propagation.
Carry it through `FeatureTaskRuntimeRunRequest`, phase launch composition, and
the review prompt, including every rerun of the review phase. Update the goal
driver so runtime child feature-task runs receive the selection and durable
goal resume retains it for pending subtasks.

Add a goal-continuation review policy that persists a per-subtask pass counter
and `review_cap_reached` disposition, together with the immutable per-subtask
`review_base_sha`. It must make the runtime advance after the second pass
without forging an approved verdict, prevent an audit-driven third review,
preserve all findings, invoke every subtask review against its base-to-current
worktree delta rather than `origin/main...HEAD`, and expose a compact
class/symbol-only summary in goal output and goal events.

The review-phase prompt must instruct the phase agent to invoke the routed
review with the canonical mode request and, when configured, the existing
parallel-review request. It must not merely mention the desired mode in prose
while leaving the actual `bill-code-review` invocation unconstrained.

### 4. Tests and generated runtime refresh

Add focused tests for argument parsing, default compatibility, invalid input,
confirmation/handoff wording, runtime request and prompt propagation, goal
child propagation, durable resume/review-loop reuse, explicit inline rejection
for an ineligible scope, forced delegated execution for an otherwise eligible
scope, and composition with the parallel-review lane.

Add goal-specific tests for two total review passes, parallel-lane accounting,
review-cap continuation with unresolved Blocker/Major findings, audit-gap
behavior after the cap, crash/resume persistence, final outcome projection, and
compact output that includes class/symbol labels but contains no file path or
line reference. Test initial, repaired, resumed, and later-subtask reviews
against a persisted `review_base_sha`, proving that each sees the complete
current subtask delta (including staged, unstaged, and owned untracked files)
but excludes earlier subtask commits and never falls back to
`origin/main...HEAD`. Prove that full raw review artifacts and telemetry imports
retain their existing location-bearing evidence.

Refresh local installed skill staging with `./install.sh` after authored skill
or rendering changes. Validate the changed skill sources and run the relevant
Kotlin test suites before handoff.

## Acceptance Criteria

1. `/bill-feature <issue-key> code-review:inline` and `code-review:delegated` are accepted and shown in the single feature confirmation gate; an omitted argument retains automatic review-mode selection.
2. `code-review:auto` is accepted as the explicit spelling of the default, while malformed, unknown, duplicate, or conflicting `code-review:` values fail loudly before confirmation, workflow opening, or phase launch.
3. `code-review:delegated` always uses the normal delegated routed-review path, even for an inline-eligible diff; inability to start required delegated workers blocks loudly and never falls back to inline review.
4. `code-review:inline` uses the normal inline review path only when every existing inline-eligibility condition passes. An ineligible, high-risk, multi-layer, oversized, or ambiguous scope is rejected with a reason that delegated review is required; it is not silently changed to another mode.
5. The selection is passed through single-spec runtime and prose feature-task paths into the actual `bill-code-review` invocation, and every review fix-loop or audit-driven re-review retains the same selected mode.
6. A decomposed feature started through `bill-feature` carries the selection through both runtime and prose goal runners to every child task; resumed parent or child workflows retain the original selection unless an explicitly documented compatibility rule rejects an attempted change.
7. `parallel-review:<agent>` remains a second full lane. When combined with a code-review selection, both lanes receive the intended review-mode contract without recursive parallel launches, merged findings retain their existing provenance format, and both lanes count as one goal review pass.
8. `bill-code-review` exposes one canonical execution-mode argument that uses the shared eligibility/delegation policy, so standalone and feature-owned review do not drift in thresholds, high-risk handling, output metadata, telemetry, or specialist routing.
9. Each decomposed-goal subtask captures and durably persists `review_base_sha` before implementation changes. Every review pass, including a repair re-review, audit-driven re-entry, or resume, reviews the complete current delta from that base through the subtask's staged, unstaged, and owned untracked changes.
10. A goal-subtask review never uses `origin/main...HEAD`, the entire feature branch, or committed changes from an earlier subtask. If its persisted baseline cannot be used safely, the subtask blocks loudly rather than substituting a broader scope.
11. For every decomposed-goal review pass, standard goal output and goal events emit a compact finding summary with subtask id, pass number, verdict/continuation state, severity, class-or-symbol label, and concise finding text, but no path, line number, hunk, or raw child-review output. Full location-bearing review evidence remains available in durable artifacts and telemetry.
12. Each decomposed-goal subtask runs at most two total review passes across initial review, review-fix re-review, audit-gap re-entry, and resume. Parallel lanes together count as one pass, and a crash/resume never resets the count.
13. When a goal subtask reaches the two-pass cap with unresolved Blocker or Major findings, the complete findings and `review_cap_reached` disposition are durably recorded, the compact summary is emitted, and the goal advances to its next normal phase without falsely reporting approval or starting a third review pass.
14. The review cap continuation policy does not change the existing single-spec/standalone feature-task review cap or blocking behavior, and it does not suppress audit, validation, dependency, commit, or final reporting gates.
15. Existing feature invocations using only `mode:runtime|prose` and/or `parallel-review:<agent>` retain their current behavior, including one confirmation gate, default automatic review selection, and all workflow/telemetry contracts.
16. Focused source and Kotlin tests cover parsing, routing, invalid input, prompt construction, runtime and goal propagation, durable resume, review-loop reuse, eligibility rejection, forced delegation, parallel composition, two-pass goal continuation, subtask-delta scope, compact no-path output, raw-artifact preservation, and default compatibility; the required validation commands pass.

## Non-goals / Constraints

- Do not replace the existing automatic eligibility policy, revise its size/risk thresholds, or make high-risk review inline-capable.
- Do not use this feature to change the provider-specific delegated-worker contract or add support for a runtime that cannot currently delegate.
- Do not apply the two-pass continuation policy to standalone/single-spec feature tasks.
- Do not conflate execution-mode selection with parallel review, phase-agent assignment, model selection, feature flags, or feature workflow `mode:runtime|prose`.
- Do not use `origin/main...HEAD`, a whole feature-branch diff, or an arbitrary current `HEAD` as a substitute for the persisted subtask review baseline.
- Do not replace full location-bearing review artifacts or telemetry with the compact goal display; the rendering is an operator-facing projection only.
- Do not create new user-facing platform lists, hard-code pack names, or bypass manifest-driven stack routing and add-on selection.
- Do not hand-author generated `SKILL.md` wrappers, support pointers, provider-native agent outputs, or installed staging artifacts.

## Validation Strategy

Run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
```

Then confirm the installed `bill-feature` and `bill-code-review` artifacts
contain the new arguments and that generated support pointers/native-agent
output remain untracked.

## Next Path

Run bill-feature on `.feature-specs/SKILL-119-code-review-mode-selection/spec.md`.
