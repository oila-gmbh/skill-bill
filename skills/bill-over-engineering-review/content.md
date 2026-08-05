---
name: bill-over-engineering-review
description: Review code for unnecessary complexity and bloat. Use when asking for an over-engineering review, finding bloat, or simplifying.
---

# Over-Engineering Review Content

Review exclusively for unnecessary complexity. Report what to cut and what replaces it.
Correctness, security, and performance stay out of scope — route those to `bill-code-review`.
List findings only; do not apply fixes. Do not review unit-test value; that belongs to
`bill-unit-test-value-check`.

## Scopes

- **diff** (default): current staged/unstaged changes, a commit, or a PR diff. Rank every
  finding by how much it shortens the diff.
- **repo**: whole-tree audit. Rank findings biggest cut first.

If the caller does not name a scope, use **diff**.

## Finding Format

One line per finding:

`<file>:L<line>: <tag> <what to cut>. <replacement>.`

For a contiguous span, `L<start>-<end>` is allowed in place of `L<line>`.

## Tags

- `delete:` dead code, unused flexibility, speculative feature. Replacement: nothing.
- `stdlib:` hand-rolled thing the standard library ships. Name the function.
- `native:` dependency or code doing what the platform already does. Name the feature.
- `yagni:` abstraction with one implementation, config nobody sets, layer with one caller.
- `shrink:` same logic, fewer lines. Show the shorter form.

## Repo Hunt List

In **repo** scope, hunt for:

- deps the stdlib or platform already ships
- single-implementation interfaces
- factories with one product
- wrappers that only delegate
- files exporting one thing
- dead flags and config
- hand-rolled stdlib

## Examples

Vague hedges are not findings. Use tagged one-liners:

❌ "This EmailValidator class might be more complex than necessary, have you considered
whether all these validation rules are needed at this stage?"

✅ `validators.kt:L12-38: stdlib: 27-line validator class. "@" in email, 1 line; real validation is the confirmation mail.`

✅ `dates.kt:L4: native: third-party date helper imported for one format call. platform date formatter, 0 deps.`

✅ `repo.kt:L88: yagni: AbstractRepository with one implementation. Inline it until a second one exists.`

✅ `retry.kt:L52-71: delete: retry wrapper around an idempotent local call. Nothing replaces it.`

✅ `map.kt:L30-44: shrink: manual loop builds map. associate(keys.zip(values)), 1 line.`

## Scoring

End with:

- **diff** scope: `net: -<N> lines possible.`
- **repo** scope: `net: -<N> lines possible, -<M> deps possible.`

If there is nothing to cut, say `Lean already. Ship.` and stop.

## Boundaries

Scope: over-engineering and complexity only. Correctness bugs, security holes, and
performance issues are explicitly out of scope — route them to `bill-code-review`.

Never flag for deletion:

- a minimal smoke test or self-check
- skill-bill governed contracts: typed errors, loud-fail seams, `contract_version`
  constants, parity tests, and validator-backed rules
- deliberate simplifications marked `shortcut: <ceiling>, <upgrade trigger>` (convention
  from SKILL-162 subtask 1; keep the carve-out even when that marker is not yet present
  in the reviewed tree)

Does not apply fixes; only lists them. Test-value judgment stays with
`bill-unit-test-value-check`.

## Attribution

Ponytail over-engineering review/audit mechanisms are adapted from DietrichGebert/ponytail
(MIT). Prefer the single SKILL-162 attribution already recorded in
`skills/bill-feature-task-prose/content.md` Attribution; do not triplicate license text here.
