# SKILL-221 Subtask 2: Remaining Detekt Suppressions

## Intended Outcome

Resolve D-03. After complexity suppressions are gone, the remaining detekt
silences are exception swallowing, magic numbers, line length, filename
mismatch, and similar. Remove them when a straightforward fix exists. Do
not invent awkward substitutes just to clear an annotation; anything kept
goes on the allow-list in subtask 3 (expected: few or none for this set).

## Scope

Resolve every remaining detekt (and ktlint-in-`@Suppress`) annotation whose
name is not a Kotlin compiler diagnostic. That includes at least:

- `TooGenericExceptionCaught`, `SwallowedException`,
  `TooGenericExceptionThrown`, `InstanceOfCheckForException`
- `MagicNumber`
- `MaxLineLength`, `ktlint:standard:max-line-length`
- `MatchingDeclarationName`, `ktlint:standard:filename`
- `SpreadOperator`
- `FunctionOnlyReturningConstant`

Map broad `catch (e: Exception)` to the typed failure family the boundary
already uses, or to a named expected type. Do not replace
`TooGenericExceptionCaught` with `catch (_: Throwable)` or an empty catch.
Rethrow `CancellationException` before any broad catch.

`MaxLineLength` is resolved by wrapping or extracting, not by raising 120.
`MatchingDeclarationName` / ktlint filename is resolved by renaming the
file or the type so they match.

Compiler names (`UNCHECKED_CAST`, `UNUSED_PARAMETER`, `UNUSED_VARIABLE`,
`UnusedParameter`, `UnusedPrivateProperty`) stay for subtask 3.

## Applicable Principles

- Expected failures cross a boundary as typed results.
- Collapse an unmapped throwable into an explicit `Unknown`-style case at
  the boundary; never a success-shaped fallback.
- Auto-fixers are convenience; the check stays.

## Acceptance Criteria

1. Every detekt/ktlint suppression in this set is either removed by a
   straightforward fix, or deferred to subtask 3's allow-list with a
   one-line why (and only when a fix would be a weird workaround).
2. `detekt` reports zero issues for rules this subtask un-silenced.
3. No empty or success-shaped catch remains where a suppression used to
   hide `TooGenericExceptionCaught` or `SwallowedException`.
4. Existing tests pass without assertion weakening. One test is added only
   when a previously swallowed throwable becomes a typed failure the suite
   did not cover.
5. `../../../scripts/validate` passes.

## Failure And Recovery Behavior

A previously swallowed parse or I/O failure must become the typed failure
that boundary already documents. If the boundary had no typed case, add one
in the existing family rather than a new hierarchy.

## Non-Goals

- Compiler suppressions and the architecture-test ban (subtask 3).
- Complexity rules (subtask 1).
- New failure hierarchies for unrelated stages.

## Dependency Notes

Runs after subtask 1. Must not run concurrently with it.

## Validation Strategy

`../../../scripts/validate`. Grep `@Suppress` and classify remaining names: complexity
must be zero; remaining detekt/ktlint names are either gone or queued for
subtask 3's allow-list; compiler names wait for subtask 3.

## Next Path

Subtask 3 resolves or allow-lists compiler suppressions and locks the gate.
