# Issue 333: Isolate malformed review finding citations

## Intended Outcome

Make runtime-owned review ingestion tolerant of malformed finding citations. A
single review finding with a missing, zero, negative, or otherwise invalid
citation line must not abort the complete review phase or discard valid
findings. The runtime should retain the valid findings, record an actionable
warning for the malformed citation, and leave the review workflow recoverable.

## Acceptance Criteria

1. A malformed citation line on one finding is handled at the per-finding
   boundary without throwing an uncaught exception that aborts review ingestion.
2. The malformed citation is rejected or represented as an absent citation
   according to the governed review contract, and the warning identifies the
   affected finding or citation entry.
3. Valid citations and valid findings in the same review result continue through
   parsing, verification, persistence, and review accounting unchanged.
4. Missing, zero, negative, non-numeric, and mixed valid/invalid citation inputs
   have regression coverage at the ingestion boundary.
5. A malformed citation cannot leave the runtime-owned review phase permanently
   blocked; the resulting workflow either continues with the surviving findings
   or records a retryable review outcome with durable diagnostic context.
6. Existing repository-relative path validation and positive-line behavior for
   valid citations remain enforced.
7. The feature-spec manifest and all executable subtask specs remain schema-valid
   and acceptance-criteria extractable by the goal runtime.

## Constraints

- Keep the change inside the review parsing, verification, and runtime recovery
  boundaries; do not redesign unrelated workflow phases.
- Preserve loud failure for malformed review envelopes, unknown contract values,
  invalid repository paths, and other errors that are not isolated citation
  data.
- Prefer a typed per-finding diagnostic or warning over stdout-only logging so
  the runtime can retain evidence without persisting raw model transcripts.
- Do not weaken valid citation semantics merely to accommodate malformed model
  output.
- Add only high-value tests for the mixed-finding failure mode and the affected
  recovery boundary.

## Non-Goals

- Changing the review prompt or citation format for every review provider.
- Removing citation validation or allowing arbitrary repository paths.
- Reworking review telemetry schemas unrelated to malformed citation handling.
- Redesigning operator controls for unrelated blocked review outcomes.

## Affected Areas

- `../../../runtime-kotlin/runtime-domain` review citation models and field decoding.
- `../../../runtime-kotlin/runtime-application` claim verification and review ingestion.
- Runtime-engine review phase recovery and durable outcome handling when the
  existing exception path proves involved.
- Focused domain, application, and runtime-engine regression tests.

## Validation Strategy

- Run the focused domain and application review tests covering citation decoding
  and claim verification.
- Run the focused runtime-engine review phase tests covering ingestion failure
  and retryable recovery.
- Run the repository's dominant-stack quality check for the changed Kotlin
  modules after implementation.

## Delivery Plan

1. Isolate malformed citation handling at the review ingestion boundary.
2. Preserve surviving findings and durable recovery behavior.
3. Add regression coverage for malformed and mixed citation inputs.
