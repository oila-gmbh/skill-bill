# SKILL-193 Subtask 1: Index-only parent packet contract

## Intended Outcome

The review-context wire contract and domain hunk model describe an index, not
a diff dump. Parent-packet hunks cannot carry `content`. Contract version
bumps incompatibly and 1.0 envelopes fail loudly.

## Scope

- Bump `orchestration/contracts/review-context-schema.yaml` together with
  `REVIEW_CONTEXT_CONTRACT_VERSION` (currently `"1.0"` in
  `ReviewContextSchemaPaths.kt`). Removing required `content` is incompatible;
  the new version is `"2.0"`.
- Redefine `$defs/hunk` as index-only: required `hunk_id`, `path`, `old_start`,
  `old_count`, `new_start`, `new_count`, `content_digest`, and evidence
  locator. Forbid `content` and any other diff-body property
  (`additionalProperties: false`).
- Apply the same index-only hunk shape to parent `changed_hunks` and to launch
  bundle entries that today serialize `content`.
- Add a coherence check: no parent-packet, assignment, or launch property
  accepts inlined diff bytes (same rule as the SKILL-164 shared-evidence
  projection).
- Update `ReviewChangedHunk` so the in-memory packet model used for parent
  canonicalization does not hold body text. Body bytes remain available to
  composition only through the store when computing `hunk_id` and
  `content_digest` (subtask 2). This subtask may keep a test-only factory that
  accepts body text to derive ids, but `toEnvelope()` / parent canonical
  form must not emit `content`.
- Pin schema and Kotlin constants with the existing contract-version parity
  test. Update `ReviewContextSchemaValidatorTest` fixtures that currently
  require `content`.

## Acceptance Criteria

1. `REVIEW_CONTEXT_CONTRACT_VERSION` is `"2.0"` in schema and Kotlin, and the
   pinning test fails if they diverge.
2. `$defs/hunk` requires `hunk_id`, path, spans, `content_digest`, and
   evidence locator, and rejects `content`.
3. A parent packet, assignment, or launch envelope that includes hunk
   `content` or another inlined diff-body field fails schema validation.
4. `ReviewChangedHunk.toEnvelope()` (and launch assembled-entry projection)
   emits the index fields and does not emit `content`.
5. Parent-packet canonical bytes used for `parent_packet_bytes` exclude hunk
   bodies. A unit fixture with megabyte-scale body text in the store does not
   increase parent canonical size by that amount.
6. A 1.0 envelope (`contract_version: "1.0"`) fails loudly on the 2.0
   validator with a typed contract error naming the version mismatch.
7. Existing coherence checks that hunk ids are content-addressed remain true:
   tests still prove id changes when stored body bytes change (using the
   store or a test factory, not the parent envelope).
8. Schema-to-Kotlin bijection / anchored review-context tests pass for the
   new hunk shape.

## Non-Goals

- Wiring composition to the shared evidence store (subtask 2).
- Broker batching, lane-incomplete overflow, or prompt-assembly changes
  (subtask 3).
- Raising default byte budgets.
- SKILL-191 stage semantics.

## Dependency Notes

No dependencies. Subtasks 2 and 3 consume the 2.0 hunk shape.

Coordinate with SKILL-191: bump the shared `REVIEW_CONTEXT_CONTRACT_VERSION`
once; do not leave 1.0 pins in stage-launch tests. Prefer updating fixtures
to 2.0 index hunks over editing SKILL-191 stage behaviour.

## Validation Strategy

- Schema rejection: parent packet with `changed_hunks[].content`.
- Schema acceptance: index-only hunk with digest and locator.
- Parity test: schema const vs `REVIEW_CONTEXT_CONTRACT_VERSION`.
- Domain: parent envelope canonical size independent of a large stored body
  in a fixture.
- 1.0 envelope rejected by 2.0 validator.
- Module checks for `runtime-contracts`, `runtime-domain`, and
  `runtime-core` schema tests.

## Next Path

Commit on the feature branch. Subtask 2 composes packets from the evidence
store without copying bodies into the packet.
