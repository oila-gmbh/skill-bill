---
status: Complete
issue_key: SKILL-119
parent_issue_key: SKILL-119
subtask_id: 1
---

# SKILL-119 Subtask 1: Review-selection contract and single-task propagation

## Outcome

Feature callers can select `code-review:auto|inline|delegated` once, see the
selection at the sole confirmation gate, and have the canonical execution-mode
request reach every single-spec runtime or prose review invocation.

## Scope

- Define the canonical execution-mode contract in `bill-code-review` without
  duplicating eligibility or delegation policy in feature skills.
- Parse, reject invalid combinations, display, and forward the feature-facing
  `code-review:` value through `bill-feature`, `bill-feature-task`, runtime,
  and prose task routes.
- Persist the resolved value in runtime invariants and carry it to the CLI
  request and review-phase prompt, including review-fix and audit re-entry.
- Preserve the independent `mode:runtime|prose` and `parallel-review:`
  arguments and the one-confirmation-gate contract.

## Acceptance Criteria

1. Absent `code-review:` and explicit `code-review:auto` both resolve to the
   existing automatic policy and appear as `auto (default)` when omitted.
2. `code-review:inline` and `code-review:delegated` are shown in the single
   confirmation gate and forwarded unchanged to the actual routed review call.
3. Malformed, unknown, duplicate, and conflicting review-mode arguments fail
   before confirmation, workflow opening, or phase launch.
4. Explicit delegated review always uses delegated routing; explicit inline
   review retains the shared eligibility rejection path and never silently
   changes mode.
5. Runtime invariants, CLI parsing, phase prompts, and every standalone
   re-review retain the selected mode.

## Non-goals

- Do not change review eligibility thresholds, risk classification, or worker
  capability contracts.
- Do not implement decomposed-goal baseline, cap, or compact-summary behavior.

## Dependencies

None. This subtask establishes the contract consumed by subtasks 2 and 3.

## Validation Strategy

Add focused parser, confirmation, CLI/request, prompt, forced-delegation, and
inline-rejection tests. Run the relevant runtime-domain, runtime-cli, and
runtime-application suites.

## Next Path

Proceed to subtask 2 and subtask 3 after the canonical contract is durable.
