# SKILL-221 Subtask 1: Pin Complexity Rules And Resolve Complexity Suppressions

## Intended Outcome

Resolve D-01 and D-02. A reader of `detekt.yml` can see that TooManyFunctions,
LargeClass, LongMethod, and the rest of the complexity set are on. Every
complexity `@Suppress` is gone because the code no longer violates those
rules.

## Scope

### Pin the rules

In `runtime-kotlin/config/detekt/detekt.yml` under `complexity:`, mark as
`active: true` with an explicit numeric threshold:

- `TooManyFunctions` (files, classes, interfaces, objects, enums)
- `LargeClass`
- `LongMethod`
- `CyclomaticComplexMethod`
- `ComplexCondition`
- `NestedBlockDepth` (keep 6)
- `ThrowsCount`
- `LongParameterList` (keep function 6, constructor 7)
- `ReturnCount` (keep max 4)

Also keep `style.MaxLineLength` at 120 as already written. If
`build-logic/convention` has a parallel detekt config, give it the same
complexity keys.

Do not disable `buildUponDefaultConfig`. Do not introduce a baseline file.
Do not loosen any threshold relative to current overrides or detekt
defaults.

### Resolve complexity suppressions

Delete every `@Suppress` / `@file:Suppress` of:

`TooManyFunctions`, `LargeClass`, `LongMethod`, `LongParameterList`,
`CyclomaticComplexMethod`, `ComplexMethod`, `ComplexCondition`,
`NestedBlockDepth`, `ReturnCount`, `ThrowsCount`,
`LoopWithTooManyJumpStatements`, `DestructuringDeclarationWithTooManyEntries`

in authored Kotlin under `runtime-kotlin/` and `runtime-kotlin/build-logic/`,
including tests.

Resolve by extraction: collaborators, parameter objects, fewer returns via
sealed results already in the type, shallower blocks. Do not split a test
class solely to dodge `LargeClass` if the suite is one contract — extract
helpers and factories instead. SKILL-220 already split production files over
500 lines; do not re-merge them.

A 2026-08-29 count (pre-SKILL-220) of these names was the bulk of production
complexity suppressions, concentrated in `FeatureTaskRuntimeRunLoop`,
`FeatureTaskRuntimePhaseRecorder`, `ParallelCodeReviewRunner`, CLI command
bags, and large test suites. Re-inventory at start; SKILL-220 should have
removed some.

## Applicable Principles

- The check stays in the gate; a suppression is not a check.
- Prefer clear names and small functions over comments.
- Do not loosen a threshold to hide a finding.

## Acceptance Criteria

1. `detekt.yml` names each complexity rule listed above as `active: true`
   with a numeric threshold. None of those keys is absent.
2. No authored Kotlin file contains a suppression of those rule names.
3. `detekt` reports zero issues for those rules.
4. No production file exceeds 500 lines.
5. No new public type exists whose only caller is the file it was extracted
   from.
6. Existing tests pass without assertion weakening.
7. `scripts/validate` passes.
8. No test is added except where extraction changes an observable failure
   path (then one test, named for that regression).

## Failure And Recovery Behavior

Unchanged unless an extraction forces a typed result that was previously an
early return; that result must match the existing failure family.

## Non-Goals

- `TooGenericExceptionCaught`, `UNCHECKED_CAST`, MagicNumber, MaxLineLength
  suppressions, and the suppression ban (subtasks 2 and 3).
- Turning on documentation comment rules.

## Dependency Notes

Starts after SKILL-220 subtasks 4–6. Lands before subtasks 2 and 3 of this
goal.

## Validation Strategy

`scripts/validate`. Grep the complexity rule names inside `@Suppress` and
confirm zero authored hits. Open `detekt.yml` and confirm each key.

## Next Path

Subtask 2 resolves the remaining detekt suppressions.
