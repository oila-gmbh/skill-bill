# SKILL-221: Detekt Complexity Gate And Suppression Elimination

## Intended Outcome

Detekt is already on the `check` path (`skillbill.quality`,
`buildUponDefaultConfig = true`, `maxIssues: 0`), but
`../../../runtime-kotlin/config/detekt/detekt.yml` only names a handful of rules.
Complexity rules such as `TooManyFunctions`, `LargeClass`, `LongMethod`, and
`CyclomaticComplexMethod` are therefore implicit defaults, and the tree
silences them with `@Suppress` instead of fixing the code.

A 2026-08-29 scan found **657** `@Suppress` / `@file:Suppress` rule mentions
in **259** Kotlin files (446 production, 211 test): `UNCHECKED_CAST` 124,
`LongParameterList` 90, `TooManyFunctions` 88, `TooGenericExceptionCaught` 62,
`LongMethod` 55, `LargeClass` 42, `ReturnCount` 42, `MaxLineLength` 40,
`CyclomaticComplexMethod` 28, plus `ThrowsCount`, `SwallowedException`,
`MagicNumber`, compiler unused-parameter names, and ktlint filename/line
suppressions.

This program pins the complexity set in `detekt.yml`, removes suppressions
that hide real structure or typed-failure debt, and leaves only a small
allow-list of suppressions that are honest — where removing them would mean
a weird workaround rather than a clearer design.

### What "reasonable" means

- **Remove** when the finding names real debt: god class, too many functions,
  long method, swallowed / generic catch, magic number that should be a
  named constant, filename mismatch, unused parameter you control.
- **Keep** when the alternative is worse: a local `@Suppress("UNCHECKED_CAST")`
  at a decode or interop edge where a typed decoder does not exist yet and
  inventing a one-off wrapper only to delete the annotation would obscure
  the cast. Prefer a shared typed helper when one already fits; do not invent
  ceremony just to clear the annotation.
- **Never keep** complexity suppressions (`TooManyFunctions`, `LargeClass`,
  `LongMethod`, `CyclomaticComplexMethod`, `ComplexCondition`,
  `NestedBlockDepth`, `ReturnCount`, `ThrowsCount`, `LongParameterList`,
  and siblings). Those are fixed by extraction, not by annotation.
- **Never** loosen thresholds, add a detekt baseline, or replace a
  suppression with a comment that only excuses it.

Every retained suppression is narrow (declaration-scoped, not `@file:` when
one site suffices), names the rule, and is listed in a dated allow-list
entry (path + symbol + rule + one-line why) in
`../../../runtime-kotlin/agent/decisions.md`. The allow-list starts empty and grows
only for sites that survive an honest "fix vs keep" judgment — not as a
pre-filled exemption dump.

Depends on SKILL-220's oversized splits (subtasks 4–6). Do not start this
goal until those splits have landed; they delete complexity suppressions
that file size alone caused. This program owns everything that remains.

Generated sources under `build/` and SQLDelight/generated sets are **out of
scope** (already excluded from detekt).

## Principle Sources

- skill-bill-v2 `../../../docs/code-principles.md` Build And Tooling: auto-fixers are
  convenience; the corresponding check stays in the validation gate. A fixer
  never replaces an enforcer.
- This repository's AGENTS.md: prefer extraction over comments; do not add
  comments that only excuse a suppression.
- SKILL-220: 500-line production-file ceiling and "delete suppressions the
  split made unnecessary; do not add new ones."

## Audit Finding Ownership

| Finding | Severity | Problem | Owning subtask |
| --- | --- | --- | --- |
| D-01 | Major | `detekt.yml` does not mark complexity rules; they exist only as defaults. | 1 |
| D-02 | Major | Complexity suppressions hide structure debt instead of splitting it. | 1 |
| D-03 | Medium | Exception / style / naming / ktlint suppressions that are usually fixable. | 2 |
| D-04 | Medium | `UNCHECKED_CAST` and unused-symbol suppressions — many fixable, some honest. | 3 |
| D-05 | Major | Nothing fails the build when a new complexity (or other non-allow-listed) suppression is added. | 3 |

## Scope

- Pin complexity rules as `active: true` with explicit numeric thresholds in
  `../../../runtime-kotlin/config/detekt/detekt.yml` (and build-logic's detekt block
  if it has one).
- Remove every complexity suppression by fixing the code.
- Remove other detekt/ktlint suppressions when a straightforward fix exists;
  do not invent awkward substitutes.
- For compiler suppressions (`UNCHECKED_CAST`, unused symbols): fix when a
  clear typed path exists; retain only when the cast or unused override is
  the honest shape, on the allow-list with a one-line why.
- Add an architecture test that bans complexity suppressions entirely and
  bans any other `@Suppress` not on the allow-list.

`intellij-plugin` does not apply detekt today. Resolve any suppressions
already there the same way; do not newly wire detekt into that module in
this program.

## Applicable Principles And Invariants

- The check stays in the validation gate. A suppression is not a check —
  except the rare allow-listed case where the check would force a worse API.
- Extract collaborators and typed catches rather than silencing complexity
  or exception rules.
- Do not loosen thresholds. Keep existing overrides: `NestedBlockDepth` 6,
  `ReturnCount` 4, `LongParameterList` function 6 / constructor 7,
  `MaxLineLength` 120. Newly named complexity rules use detekt defaults.
- No detekt baseline.
- SKILL-220's 500-line ceiling remains; do not re-merge split files.
- Behavior parity: existing tests pass without weakened assertions.

## Implementation Sequence

1. Pin complexity rules; remove every complexity suppression.
2. Resolve remaining detekt/ktlint suppressions that are straightforward to
   fix; allow-list only if a fix would be a weird workaround (expected: few
   or none).
3. Resolve or allow-list compiler suppressions; lock the gate with an
   architecture test (complexity ban + allow-list parity).

## Acceptance Criteria

1. `detekt.yml` explicitly marks at least `TooManyFunctions`, `LargeClass`,
   `LongMethod`, `CyclomaticComplexMethod`, `ComplexCondition`,
   `NestedBlockDepth`, `ThrowsCount`, `LongParameterList`, and `ReturnCount`
   as `active: true` with numeric thresholds.
2. Thresholds are not looser than this repo's pre-program overrides or, for
   newly named rules, detekt defaults.
3. Authored Kotlin contains **no** complexity-rule `@Suppress` /
   `@file:Suppress` / `@SuppressWarnings`.
4. Every remaining non-complexity `@Suppress` is on the dated allow-list
   (path, symbol, rule, one-line why). Sites that were fixed are not listed.
5. No retained suppression exists only to dodge a rule whose fix is
   extraction, a named constant, a rename, or deleting an unused symbol.
6. `./gradlew detekt` (via `../../../scripts/validate`) reports zero issues with
   `maxIssues: 0` and no baseline file.
7. No production file exceeds SKILL-220's 500-line ceiling.
8. An architecture test fails on a new complexity suppression and on any
   `@Suppress` not on the allow-list, proved against fixtures.
9. `../../../scripts/validate` passes at every subtask boundary and at program
   completion.
10. Tests may change structure but must not weaken assertions to pass.

## Public Contracts Introduced Or Changed

None. Detekt configuration is a build gate, not a runtime wire contract.

## Constraints

- Do not start until SKILL-220 subtasks 4–6 are complete.
- One subtask per commit, each green under `../../../scripts/validate`.
- Do not add a detekt baseline. Do not set `maxIssues` above 0.
- Do not disable a rule to clear suppressions.
- Do not invent typed wrappers, extra modules, or API churn whose only
  purpose is deleting a legitimate `UNCHECKED_CAST` (or similar).
- Do not add comments whose only job is to excuse a suppression; the
  allow-list entry is the justification.
- No new module, no new dependency, no DI framework change.
- Tests added by this program must name the regression they catch.

## Non-Goals

- SKILL-220 package clustering, FQN sweep, failure-identity, or the
  500-line split program itself.
- Zero authored `@Suppress` at all costs (including weird workarounds).
- Turning on detekt comment rules.
- Introducing Konsist or ArchUnit.
- Newly applying detekt to `intellij-plugin`.
- Changing ktlint indent or line-length policy except deleting suppressions
  that hide violations of the existing 120-character gate.

## Dependency And Coordination Notes

Successor to SKILL-220, not a parallel track on the same god objects.
Subtasks 1–3 of this goal run in order: complexity first, then other
detekt, then compiler + allow-list lock.

## Validation Strategy

Each subtask runs `../../../scripts/validate`. Subtask 3 proves the architecture
scanner against fixtures (complexity suppression; non-allow-listed
suppression), then reverts. Program complete when complexity suppressions
are gone, remaining suppressions match the allow-list 1:1, and
`../../../scripts/validate` passes.

## Next Path

Begin with subtask 1 after SKILL-220 oversized splits have landed.
