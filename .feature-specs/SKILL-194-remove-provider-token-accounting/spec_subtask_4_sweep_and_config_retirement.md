# SKILL-194 Subtask 4 — Planning-sweep counters and repo-local config retirement

## Scope

Two small, independent surfaces that both read provider token values.

**Planning sweep.** Delete `inputTokens` and `outputTokens` from `GoalPlanningSweepModels`
(`:104-105`), their rendering in the sweep progress line (`:119-122`, which currently prints
`inputTokens=<n> outputTokens=<n>` or `unknown`), and the assignment from the launch outcome in
`GoalPlanningSweep.kt:1266-1267`. The progress line keeps every non-token field it renders today; only
the two token clauses come out, and the surrounding text must read naturally without them rather than
leaving a dangling separator.

**Repo-local config.** Delete the `provider_token_thresholds` read in
`FileSystemRepoLocalConfig.kt:106` and the `providerTokenThresholds` it populated on the budget policy.
An existing `.skill-bill/config.yaml` that still declares the block must parse successfully with the
key ignored through the subtask 1 seam and one degradation record emitted — never rejected, and never
requiring the operator to edit their config before upgrading.

This is the operator-visible half of the program. A repository that upgrades with a
`provider_token_thresholds` block in place must see no error, no warning that demands action, and no
behaviour change beyond the accounting disappearing.

## Acceptance Criteria

1. `GoalPlanningSweepModels` carries no `inputTokens` or `outputTokens`, and `GoalPlanningSweep` no
   longer assigns them from the launch outcome.
2. The planning sweep progress line renders every field it renders today except the two token clauses,
   and reads as well-formed text with no dangling separator or empty clause.
3. `FileSystemRepoLocalConfig` performs no `provider_token_thresholds` read, and
   `ReviewContextBudgetPolicy` has no `providerTokenThresholds` to populate.
4. A `.skill-bill/config.yaml` containing a `provider_token_thresholds` block parses successfully, the
   key is ignored, and one degradation record is emitted per `docs/observability-policy.md`.
5. A `.skill-bill/config.yaml` containing no `provider_token_thresholds` block parses successfully with
   no degradation record.
6. Every other repo-local config field, including `review_context_budget.max_parent_packet_bytes` and
   `validation_gate`, resolves exactly as before.
7. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Editing any checked-in or operator `.skill-bill/config.yaml`. The key is tolerated on read, not
  migrated out of anyone's file.
- Emitting a warning that asks the operator to remove the block. The degradation record is diagnostic,
  not a call to action.
- Touching any other repo-local config field or the `validation_gate` surface.
- Touching review, goal-runner, transport, or telemetry surfaces.

## Dependency Notes

Depends on subtask 1 for config-parse tolerance and its degradation record. Depends on subtask 2, which
removes `providerTokenThresholds` from `ReviewContextBudgetPolicy` — this subtask removes the config
reader that populated it, so landing it before subtask 2 would leave the field with no producer.

## Validation Strategy

- Parse a `.skill-bill/config.yaml` containing a full `provider_token_thresholds` block; assert success,
  assert the resolved budget policy carries no token thresholds, and assert one degradation record.
- Parse a config with no such block; assert success and no degradation record.
- Parse a config carrying both `provider_token_thresholds` and `review_context_budget.max_parent_packet_bytes`;
  assert the byte budget resolves to its declared value.
- Assert the planning sweep progress line for an outcome that previously reported token counts renders
  correctly and mentions no tokens.

Update `FileSystemRepoLocalConfigTest` by deleting its token-threshold assertion (`:206`) and adding the
legacy-block tolerance case. Update `GoalPlanningSweepTest`'s progress-line expectation. Then
`(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-194
```
