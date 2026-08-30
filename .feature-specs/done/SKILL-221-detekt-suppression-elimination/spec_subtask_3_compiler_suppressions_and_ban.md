# SKILL-221 Subtask 3: Compiler Suppressions And Allow-List Lock

## Intended Outcome

Resolve D-04 and D-05. Fix compiler suppressions that are straightforward.
Retain only honest ones (especially some `UNCHECKED_CAST` sites) on a dated
allow-list. Ban complexity suppressions forever, and ban any other
`@Suppress` that is not on that allow-list.

## Scope

### Compiler suppressions

For each `@Suppress` / `@file:Suppress` of:

- `UNCHECKED_CAST`
- `UNUSED_PARAMETER`, `UnusedParameter`
- `UNUSED_VARIABLE`, `UnusedPrivateProperty`

Apply this judgment:

1. **Fix** when a clear path exists: typed decode / existing contract
   decoder / `reified` helper; delete or use an unused symbol; `_` for an
   unused override parameter the compiler accepts without a suppression.
2. **Keep** when the cast (or rare unused override) is the honest shape and
   the alternative is a one-off wrapper or API churn whose only purpose is
   deleting the annotation. Prefer a shared typed helper when one already
   fits the boundary; do not invent ceremony.
3. Every kept site is declaration-scoped (not `@file:` unless every
   suppression in the file is allow-listed), and gets one allow-list row:
   path, symbol, rule, one-line why.

Do not keep `UNUSED_*` just to preserve a dead parameter you own — delete
or use it.

### Allow-list

Live in `../../../runtime-kotlin/agent/decisions.md` (or a sibling inventory the
architecture test parses — one place, dated). Starts empty at the beginning
of this subtask; ends containing only sites that survived the judgment
above. Complexity rule names never appear on the allow-list.

Generated `build/` / `generated/` trees stay out of scope.

### Architecture test

Add one source-scanning test in `skillbill.architecture`:

- Fail on any complexity-rule `@Suppress` / `@file:Suppress` /
  `@SuppressWarnings` in authored Kotlin under `../../../runtime-kotlin` and
  `../../../runtime-kotlin/build-logic` (excluding `**/build/**`, `**/generated/**`).
- Fail on any other authored `@Suppress` whose (file, rule) pair is not on
  the allow-list.
- Prove against fixtures: a `TooManyFunctions` suppression fails; an
  allow-listed `UNCHECKED_CAST` passes; a non-allow-listed `UNCHECKED_CAST`
  fails.
- Assert `detekt.yml` still pins the complexity keys from subtask 1
  (`active: true`).

## Applicable Principles

- Prefer a clear cast at the boundary over a fake-typed wrapper that only
  hides it.
- Before writing a test, name the realistic bug it would catch.
- One strong test for the ban + allow-list parity.

## Acceptance Criteria

1. No authored complexity-rule suppression remains.
2. Every remaining authored `@Suppress` is on the allow-list with path,
   symbol, rule, and one-line why; allow-list and tree are 1:1.
3. No allow-list entry exists for a site that was fixed, or for a complexity
   rule.
4. `../../../scripts/validate` passes with `detekt` at `maxIssues: 0` and no baseline.
5. Architecture fixtures prove: complexity suppression fails; allow-listed
   cast passes; non-allow-listed cast fails; removing a pinned complexity
   key from `detekt.yml` fails.
6. No test duplicates SKILL-220's line-ceiling or FQN scanners.
7. `../../../scripts/validate` passes.

## Failure And Recovery Behavior

A new complexity suppression, or any suppression not on the allow-list,
fails `check` with file path, rule name, and the instruction to fix the
finding or add a dated allow-list row with a real why.

## Non-Goals

- Re-doing complexity or exception refactors from subtasks 1 and 2.
- Zero `@Suppress` including weird workarounds.
- Banning suppressions inside generated code.
- Enforcing comment quality.

## Dependency Notes

Runs last. Depends on subtasks 1 and 2.

## Validation Strategy

Introduce a local `@Suppress("TooManyFunctions")`, confirm failure, revert.
Introduce a non-allow-listed `@Suppress("UNCHECKED_CAST")`, confirm failure,
revert. Confirm each allow-listed site still compiles and is listed. Run
`../../../scripts/validate`.

## Next Path

Program complete. Record the allow-list policy in
`../../../runtime-kotlin/agent/decisions.md`. SKILL-220 documentation reconciliation
may cite this gate if that goal is still open.
