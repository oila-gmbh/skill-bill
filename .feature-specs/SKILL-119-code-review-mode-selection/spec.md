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

The feature must preserve the governed safety boundary: an explicit inline
request is never permitted to bypass the existing inline-eligibility rules.

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
level skill would not be sufficient: the request currently crosses authored
feature routers, the prose orchestrator, the feature-task runtime CLI and
phase prompt, and the decomposed-goal child launch path.

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

### Handoff and durability

The selected mode must appear in the existing single confirmation gate along
with feature execution mode and any parallel-review agent. The value is then
forwarded without reinterpretation through:

1. `bill-feature` to either the single-task or decomposed-goal route;
2. `bill-feature-task` to prose or runtime execution;
3. the runtime CLI request, application request, and review-phase prompt; and
4. the runtime and prose goal child-task paths.

Persist the resolved selection as run configuration in the existing durable
workflow/goal state before review can run. A review-fix loop, an audit-driven
re-review, or a resume without a new selection must reuse that stored value.
An explicit value on a resume may not silently change a review already in
progress; reject an incompatible change loudly or retain the stored value with
an explicit, documented precedence rule. Keep durable workflow records
schema-valid and do not weaken their loud-fail behavior.

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

For decomposed prose goals, include the resolved selection in each
self-contained subtask briefing/continuation context so every subtask observes
the same choice. Preserve the current one-confirmation-gate rule.

### 3. Runtime and goal propagation

Add a typed runtime representation of the selection, a validated
`skill-bill feature-task` option, and the corresponding goal-run propagation.
Carry it through `FeatureTaskRuntimeRunRequest`, phase launch composition, and
the review prompt, including every rerun of the review phase. Update the goal
driver so runtime child feature-task runs receive the selection and durable
goal resume retains it for pending subtasks.

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
7. `parallel-review:<agent>` remains a second full lane. When combined with a code-review selection, both lanes receive the intended review-mode contract without recursive parallel launches, and merged findings retain their existing provenance format.
8. `bill-code-review` exposes one canonical execution-mode argument that uses the shared eligibility/delegation policy, so standalone and feature-owned review do not drift in thresholds, high-risk handling, output metadata, telemetry, or specialist routing.
9. Existing feature invocations using only `mode:runtime|prose` and/or `parallel-review:<agent>` retain their current behavior, including one confirmation gate, default automatic review selection, and all workflow/telemetry contracts.
10. Focused source and Kotlin tests cover parsing, routing, invalid input, prompt construction, runtime and goal propagation, durable resume, review-loop reuse, eligibility rejection, forced delegation, parallel composition, and default compatibility; the required validation commands pass.

## Non-goals / Constraints

- Do not replace the existing automatic eligibility policy, revise its size/risk thresholds, or make high-risk review inline-capable.
- Do not use this feature to change the provider-specific delegated-worker contract or add support for a runtime that cannot currently delegate.
- Do not conflate execution-mode selection with parallel review, phase-agent assignment, model selection, feature flags, or feature workflow `mode:runtime|prose`.
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
