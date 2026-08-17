# SKILL-191 · Subtask 1 — Stage contract and schema versioning

## Scope

Add the contract surface every later subtask consumes. No stage runs yet; this
subtask ships the schema, the versioned Kotlin constants, the typed error, and the
parse-seam enforcement.

Extend `orchestration/contracts/review-context-schema.yaml` with four `$defs`,
modelled on the existing `integration_launch` definition:

- `spec_intent_projection` — intended outcome, acceptance criteria, constraints,
  non-goals, deferred items, provenance (spec path and content digest), and a
  declared byte budget.
- `verification_launch` — the stage 1 worker projection: review identity, one
  finding verbatim, the cited region, the delta reference, evidence surface rules,
  dependency allowlist, `forbidden_rediscovery`, broker id, isolation, budget. It
  admits no spec projection field at all, so contamination is a schema error rather
  than a convention.
- `adjudication_launch` — the stage 2 worker projection: review identity, the
  surviving finding with its stage 1 verdict, the spec intent projection, the cited
  region, and the same bounded-evidence fields.
- `finding_verdict` — the durable per-finding result: `stage`, `finding_ref`,
  `claim_verdict` (`confirmed` | `refuted` | `unresolved`), optional
  `scope_disposition` (`in_scope` | `out_of_scope_preexisting` | `spec_deviation` |
  `spec_accepted_tradeoff`), `citations`, `severity_adjustment` with direction and
  justification, and `recorded_at`.

Bump the schema `contract_version` from `0.9`, mirror it in the Kotlin
`REVIEW_CONTEXT_CONTRACT_VERSION` constant, and extend the typed
`InvalidReviewContextSchemaError` to name the failing definition. Keep the classpath
`Copy` task's `inputs.file` entry and `doFirst {}` existence guard covering the
contract file.

Schema-level rules to encode where JSON Schema can express them:

- `verification_launch` sets `additionalProperties: false` and declares no spec field.
- `finding_verdict` requires at least one citation when `claim_verdict` is `refuted`.
- `finding_verdict` requires at least one citation when `severity_adjustment`
  lowers severity or `scope_disposition` is `out_of_scope_preexisting`.
- `scope_disposition` is absent unless `stage` is `adjudication`.

## Acceptance Criteria

1. `review-context-schema.yaml` defines `spec_intent_projection`, `verification_launch`, `adjudication_launch`, and `finding_verdict` as Draft 2020-12 definitions with `additionalProperties: false`.
2. The schema `contract_version` is bumped and the Kotlin `REVIEW_CONTEXT_CONTRACT_VERSION` constant matches it, pinned by a parity test following the `PlatformPackSchemaContractVersionTest` pattern.
3. `verification_launch` declares no spec-projection field, and a payload carrying one is rejected by schema validation rather than by runtime convention.
4. A `finding_verdict` with `claim_verdict: refuted` and no citation fails validation.
5. A `finding_verdict` whose `severity_adjustment` lowers severity, or whose `scope_disposition` is `out_of_scope_preexisting`, and which carries no citation, fails validation.
6. A `finding_verdict` carrying `scope_disposition` while `stage` is `verification` fails validation.
7. Every parse seam for the new definitions raises the typed `InvalidReviewContextSchemaError` naming the failing definition, and no seam degrades silently.
8. The contract file remains covered by the classpath `Copy` task's `inputs.file` declaration and its `doFirst {}` existence guard.

## Non-Goals

- Running any stage or launching any worker.
- Persisting verdicts; subtask 2 owns storage.
- Resolving specs; subtask 3 owns the projection producer.
- Changing existing `$defs` beyond the version bump and any field additions the new
  definitions require.

## Dependency Notes

No dependencies. Every later subtask depends on this one — subtasks 2 and 3 directly,
the rest transitively.

## Validation Strategy

- One parity test pinning schema version to the Kotlin constant.
- One acceptance and one rejection test per encoded cross-field rule (criteria 3–6),
  asserting the typed error and its named definition. No literal-variation siblings.
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate`.

## Next Path

Subtask 2 — durable review stage state and resume boundaries.
