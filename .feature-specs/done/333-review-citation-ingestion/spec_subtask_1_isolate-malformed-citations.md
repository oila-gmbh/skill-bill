# Issue 333 · Subtask 1 — Isolate malformed citations

## Scope

Trace the runtime-owned review finding path from structured review output
through citation decoding, claim verification, durable review state, and phase
outcome handling. Change the narrowest boundary that currently lets one
malformed citation throw out of the finding loop.

For each malformed citation line, retain the finding when the surrounding
finding data is usable, omit or normalize only the invalid citation according
to the existing contract, and emit a diagnostic that identifies the finding or
citation entry. Keep valid findings and citations in the same result flowing
through the existing persistence and accounting paths. If the current
review-phase exception path still blocks after per-finding isolation, make that
specific outcome retryable with durable context rather than requiring an
unrelated replan.

Likely implementation seams include
`ReviewFindingFieldCodec`, `ReviewFindingCitation`, claim verification result
parsing, and the runtime-engine review phase driver. Confirm the actual seam
before editing and avoid broad changes to unrelated review errors.

## Acceptance Criteria

1. A citation with a missing, zero, negative, or non-numeric line does not
   abort parsing of the containing review result.
2. The malformed citation is omitted or normalized without constructing an
   invalid `ReviewFindingCitation`, and a diagnostic identifies the affected
   finding or citation entry.
3. A result containing both malformed and valid citations preserves every valid
   citation and every otherwise usable finding through the existing review
   ingestion and persistence path.
4. Existing non-blank repository-relative path validation and positive-line
   validation remain enforced for citations that are accepted.
5. Regression tests cover missing, zero, negative, non-numeric, and mixed
   valid/invalid citation inputs, including the observable warning or diagnostic.
6. If the malformed input reaches the runtime phase driver, the review outcome
   is continueable or retryable with durable context and does not strand the
   committed subtask in an unrecoverable blocked state.
7. Focused tests for the affected domain, application, and runtime-engine paths
   pass, and the dominant-stack quality check reports no new findings.

## Non-Goals

- Changing the provider review prompt or the general review finding grammar.
- Accepting blank or repository-external paths.
- Removing validation for malformed review envelopes or unknown contract values.
- Refactoring unrelated review persistence, telemetry, or operator controls.

## Dependency Notes

- None. This is the only executable subtask and runs from `main`.
- The implementation must preserve the existing generated feature-spec
  boundary; `../..` remains workflow input and is not product output.

## Validation Strategy

1. Run the focused `runtime-domain` tests for `ReviewFindingFieldCodec` and
   citation model behavior.
2. Run the focused `runtime-application` tests for claim verification result
   parsing and surviving findings.
3. Run the focused `runtime-engine` tests for review ingestion and retryable
   phase outcomes.
4. Run the dominant-stack Kotlin quality check required by the selected pack.

## Next Path

After this subtask, the goal runner records the reviewed commit and completes
the feature when all acceptance criteria and validation checks pass.
