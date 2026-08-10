# SKILL-180 · Subtask 3: Runtime-measured suppression diff gate

## Scope

Stop the runtime from taking the agent's word that a green gate was reached
honestly. Subtask 1 tells the agent the no-suppression rule; subtask 2 makes the
runtime own gate execution; this subtask makes the runtime measure whether the
change bought its green by silencing findings.

**A. Measure the suppression delta from repository evidence**

Add a runtime capability that counts suppression-marker occurrences in the run's
changed paths at the validate boundary and compares them against the same paths at
the run's base ref. The delta — markers the run introduced — is the gate input.

- The measurement reads the repository through the existing git operations port.
  It is read-only repository fact.
- Never derive the count from anything the agent reports.
- Scope to the run's changed paths, resolved the same way other validate-boundary
  scopes are, so a concurrently dirty tree cannot inflate the delta.
- Compare against the base ref so pre-existing suppressions are never counted. A
  file that merely moved must not read as newly suppressed.

**B. Stack-agnostic markers**

Markers are declared by the platform pack, reusing the declaration seam subtask 2
establishes for the gate command. Kotlin declares `@Suppress` / `@file:Suppress`;
Swift declares `swiftlint:disable`; PHP declares its PHPStan/Psalm baseline
additions; Go and Rust declare theirs. A repository whose pack declares no markers
is not gated. A malformed declaration loud-fails rather than degrading to an empty
marker set, which would read as an unconditional pass.

**C. Justification contract in `validation_result`**

- Each introduced suppression is accounted for with its path, the rule or check
  silenced, and a rationale for why a root-cause fix was not possible.
- The field is required *only* when the measured delta is non-zero. A clean run
  emits exactly what it emits today — no new required field, no new failure mode.
- `validation_result` stays a bounded projection: rationales are short, and raw
  command output, transcripts, and telemetry remain excluded.
- Accounting for fewer suppressions than the runtime measured is a failure, not a
  completion — mirroring how the audit phase treats an under-reported carried gap.

**D. The gate**

At the validate phase boundary: if the measured delta is non-zero and the
justification is absent or under-reports, the phase blocks with a reason naming the
offending paths and the unaccounted markers. If justified, the phase completes and
the justification is persisted durably with the phase output.

## Acceptance Criteria (this subtask)

1. The runtime measures suppression-marker occurrences in the run's changed paths
   against the base ref, reading the repository itself through the git operations
   port.
2. The measured delta is never sourced from, nor influenced by, any value the
   agent self-reports.
3. Suppressions present at the base ref are never counted as introduced, and a
   moved or renamed file does not register as newly suppressed.
4. Suppression markers are resolved from platform-pack declarations, not
   hardcoded Kotlin syntax; a repository whose pack declares no markers is not
   gated.
5. A malformed or unreadable marker declaration loud-fails rather than degrading
   to an empty marker set.
6. `validation_result` accepts a per-suppression justification carrying at least
   the path, the silenced rule or check, and a rationale.
7. The justification field is required only when the measured delta is non-zero;
   a run introducing no suppressions validates unchanged against today's schema.
8. A validate phase with a non-zero delta and absent justification blocks, and the
   blocked reason names the offending paths and the unaccounted markers.
9. A validate phase whose justification accounts for fewer suppressions than the
   runtime measured blocks rather than completing.
10. A validate phase with a fully accounted delta completes normally, and the
    justification is durably persisted with the phase output.
11. `validation_result` remains a bounded projection: no raw command output, no
    transcripts, no telemetry.
12. The gate behaves correctly under `ValidationDepth.BUILD_ONLY`, where the full
    gate is not run but suppressions can still be introduced while fixing compile
    failures.
13. Regression coverage proves the clean path, the unjustified block, the
    under-reported block, the justified pass, the no-declared-markers pass, and
    the BUILD_ONLY path.

## Non-Goals

- A detekt rule banning `@Suppress`. Considered and rejected: Kotlin-only, and it
  offers no justification path.
- Removing or refactoring the 36 existing suppressions in
  `runtime-application/src/main`.
- Gating any phase other than validate.
- Changing directive text (subtask 1) or gate execution (subtask 2).

## Dependency Notes

Depends on subtask 2, which establishes the platform-pack declaration seam this
subtask reuses for markers, and which owns the validate-boundary the gate hooks
into.

## Validation Strategy

- Unit coverage for the delta measurement: base-ref comparison, changed-path
  scoping, moved/renamed files, and the no-declared-markers case.
- Schema coverage for the conditional justification requirement, including that a
  clean run validates unchanged.
- Phase-gate coverage for block-on-absent, block-on-under-reported,
  pass-on-justified, and the BUILD_ONLY path.
- Durability coverage that the justification survives on the persisted phase
  output.
- `(cd runtime-kotlin && ./gradlew check)` with no new suppressions introduced by
  this implementation.

## Next Path

Feature complete. Open the PR for SKILL-180.
