# SKILL-189 · Subtask 1 — Durable `implement_fix` repair receipt

## Scope

Give every `implement_fix` round a durable output receipt that records what it
changed and which finding each change closes.

Today the implementation attempts ledger holds rows for `implement` — including
audit-gap repair rounds — and no row for any `implement_fix` round. Nothing in
durable state answers "what did remediation round N do".

The receipt must be finding-to-construct, not finding-to-path. In the SKILL-16
incident all four rounds touched the same three files, so a path-granular
receipt of the shape `implement` already emits would have carried no usable
signal at all.

- `runtime-kotlin/runtime-domain`: the versioned receipt model, its identity
  and budget rules, and its placement in durable review state.
- `runtime-kotlin/runtime-application`: capture at the `implement_fix` output
  seam, schema gating of the receipt, and atomic persistence alongside the
  existing round bookkeeping.
- The `implement_fix` output contract and prompt directive that instruct the
  phase to emit it.

## Acceptance Criteria

1. `implement_fix` emits a versioned repair receipt as part of its gated phase
   output, validated by the same schema path as other phase outputs.
2. The receipt records, per addressed finding: the finding's stable identity
   (severity, label, sanitized text as already carried in review state), the
   named constructs that close it, and a bounded one-line repair intent.
3. Constructs are recorded at symbol granularity — function, method, type,
   property, or file-plus-symbol — never a bare path. A receipt whose entries
   carry only paths fails validation.
4. The receipt is anchored to the round's pre-fix checkpoint sha, so a reader
   can tell which remediation delta produced it.
5. Receipts persist durably in the goal-subtask review state and survive
   process death, parent resume, and cross-run goal continuation.
6. A round that addresses several findings emits one entry per finding; a round
   that legitimately makes no edit for a carried finding records that outcome
   explicitly rather than omitting the finding.
7. Receipt size is bounded by explicit UTF-8 byte and collection budgets.
   Exceeding a budget produces a validation failure with an actionable
   payload-free reason, never a silent truncation.
8. Receipt content is sanitized: construct names and bounded intent text only —
   no diff hunks, no source bodies, no line numbers.
9. Re-entering or resuming `implement_fix` for the same round replaces that
   round's receipt idempotently rather than appending a duplicate.
10. Existing `implement_fix` behavior is otherwise unchanged: the same findings
    are carried, the same tree reconciliation semantics apply, and the
    mutating-phase idempotency contract still holds.

## Non-Goals

- No consumption of the receipt by any phase; projection is subtask 2.
- No change to the `implement` receipt shape or the audit-gap loop.
- No change to which findings reopen the loop.
- No new operator-facing command for reading receipts.

## Dependency Notes

None. This subtask is the foundation for 2, 3, and 4 — the ledger, the
`plan_fix` inputs, and churn detection all key off construct identity minted
here.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused: goal-subtask review state domain tests, the phase output validation
suite, and new receipt persistence and idempotency coverage.

## Next Path

Subtask 2 accumulates these receipts into the remediation repair ledger and
projects it into the next round.
