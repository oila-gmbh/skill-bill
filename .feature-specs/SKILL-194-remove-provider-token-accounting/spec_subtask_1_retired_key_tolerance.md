# SKILL-194 Subtask 1 — Retired-key tolerance foundation

## Scope

Build the read-side seam that makes the rest of this program invisible at every boundary, **before**
any field is deleted. Nothing is removed in this subtask; it only adds the mechanism that lets later
subtasks remove fields without a legacy record or config ever failing a read.

Deliver:

- One declared registry of retired provider-token keys, owned in one place, covering the durable and
  config wire names: `input_tokens`, `cached_input_tokens`, `output_tokens`, `reasoning_tokens`,
  `reasoning_output_tokens`, `total_tokens`, `fresh_token_approximation`, `ownership` (on usage
  objects only), `provider_token_thresholds`, `provider_token_usage`, `aggregate_direct_usage`,
  `aggregate_inclusive_usage`, `budget_regression`, and the `goal_session_accounting` artifact key.
- A normalization step applied at each read seam that strips retired keys from a decoded payload
  before it reaches schema validation, so a legacy record validates against the post-removal schema
  without the schema having to relax `additionalProperties: false`.
- A tolerated-degradation observability record per `docs/observability-policy.md`, emitted once per
  read that actually dropped at least one retired key, carrying the seam and the dropped key names —
  never a value.

Apply the seam at the three read boundaries this program will affect: the review accounting durable
read, the goal workflow artifact read, and the repo-local `.skill-bill/config.yaml` parse.

The registry must distinguish retired keys from unknown keys. An unknown key that is not on the
retired list keeps whatever loud-fail behaviour its seam has today; only listed retired keys are
tolerated. This is what keeps drift detection honest while the removal is invisible.

## Acceptance Criteria

1. A single declared registry names every retired provider-token wire key and the
   `goal_session_accounting` artifact key, and no seam hardcodes its own copy of that list.
2. A payload carrying a retired key is normalized to drop that key before schema validation at the
   review accounting durable read, the goal workflow artifact read, and the repo-local config parse.
3. A read that drops at least one retired key emits exactly one tolerated-degradation observability
   record per `docs/observability-policy.md`, naming the seam and the dropped key names.
4. The degradation record contains no token value, only key names.
5. A read that drops no retired key emits no degradation record.
6. An unknown key that is not on the retired list retains its seam's existing behaviour, so drift
   detection is unchanged.
7. Nothing is deleted in this subtask: `ProviderTokenUsage`, `ProviderTokenThresholds`,
   `GoalSessionAccounting`, and all provider token fields still exist and still behave as before.
8. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Removing any type, field, contract definition, or test. This subtask is additive.
- Relaxing `additionalProperties: false` in any schema. Tolerance is achieved by normalizing on read,
  not by widening the contract.
- Tolerating unknown keys generally, or weakening any existing loud-fail path.
- Applying the seam to read boundaries this program does not touch.

## Dependency Notes

None. This is the foundation subtask and must land first, so that no later subtask opens a window in
which a legacy record or config can fail.

## Validation Strategy

Assert observable behaviour at the three seams, not the registry's internal shape:

- A review accounting payload containing `aggregate_direct_usage` reads successfully and reports one
  degradation record naming that key.
- A goal workflow artifact map containing `goal_session_accounting` reads successfully and reports one
  degradation record.
- A `.skill-bill/config.yaml` containing `provider_token_thresholds` parses successfully and reports
  one degradation record.
- A payload with no retired keys reads successfully and reports no degradation record.
- A payload with a genuinely unknown key behaves exactly as it does before this change.

One test per rule; do not write literal-variation siblings across the retired key list. Then
`(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-194
```
