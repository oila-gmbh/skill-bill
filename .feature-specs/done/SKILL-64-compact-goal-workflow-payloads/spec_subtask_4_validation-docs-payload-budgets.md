---
status: Complete
---

# SKILL-64 Subtask 4 - Validation, Docs, and Payload Budgets

Parent spec: [.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec.md](./spec.md)
Issue key: SKILL-64

## Scope

Lock the compact payload behavior with regression tests, operator
documentation, payload-size budgets, token-accounting evidence, and attempt
ledger evidence. Document the difference between mutating `workflow continue`
and read-only inspection commands so future debugging does not accidentally
reactivate workflows or inject large JSON into agent context.

## Acceptance Criteria

1. Regression tests exercise representative large workflow artifacts for:
   - compact continuation;
   - explicit full/debug continuation or read-only show fallback;
   - compact workflow update acknowledgements.
2. Compact continuation and update acknowledgement payloads have documented byte
   budgets or size assertions that catch accidental full snapshot reintroduction.
3. Tests or fixtures prove transition-only monitoring omits routine heartbeat
   chatter while still reporting starts, phase transitions, blocked/failed
   states, completion, and sparse liveness.
4. Tests or fixtures prove best-effort token/session accounting is persisted
   when provider data exists and reports unavailable accounting without failing
   when provider data is missing.
5. Tests or fixtures prove the attempt/event ledger explains first start,
   resume/retry, terminal done check, timeout/interruption, policy-blocked, and
   final reconciled result cases without provider JSONL scraping.
6. Documentation explains:
   - `workflow continue` is mutating activation;
   - `workflow show` is read-only inspection;
   - goal child sessions should use compact continuation output;
   - full state should be fetched only when explicitly needed.
7. Documentation explains how to inspect the attempt ledger to answer why a
   subtask retried, stopped, or blocked.
8. Documentation explains that provider-reported total tokens can be dominated
   by cached input replay, and that Skill Bill optimizes payload size and
   session behavior rather than relying on cache accounting.
9. README or relevant skill content is updated if operator-facing commands or
   semantics changed.
10. Any golden/snapshot fixtures are intentionally updated and named so compact
   and full/debug shapes are easy to distinguish.
11. Standard maintainer validation passes or any unavailable command is reported
   with a concrete reason.

## Non-Goals

- Do not introduce new telemetry billing logic.
- Do not document provider-specific token cache accounting as a Skill Bill
  contract.
- Do not broaden this work into unrelated goal observability features.
- Do not make token accounting a remote telemetry requirement.

## Dependency Notes

Depends on:

- Subtask 1 compact continuation behavior.
- Subtask 2 compact update acknowledgement behavior.
- Subtask 3 goal-runner integration.

## Validation Strategy

Run targeted workflow/launcher tests first, then the standard maintainer gate:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

Run final validation and prepare the PR description for SKILL-64.

## Spec Path

.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec_subtask_4_validation-docs-payload-budgets.md
