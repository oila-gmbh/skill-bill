# SKILL-220 Subtask 8: Documentation Reconciliation And Final Verification

## Intended Outcome

Resolve P-11 and close the program. This tree has no `../../../docs/code-principles.md`
and no AGENTS Coding Conventions section. Publish the adapted principles,
name reference examples in this codebase, reconcile AGENTS.md and area
decision logs, then verify the whole program.

## Scope

- Add `../../../docs/code-principles.md`, adapted from skill-bill-v2
  `../../../docs/code-principles.md` to this hexagonal `skillbill.*` tree. State the
  rule first, then preferred shapes, then anti-patterns, then reference
  examples in this codebase to copy. Include the no-inline-FQN rule this
  program added.
- Add a Coding Conventions section to `../../../AGENTS.md` / `../../../CLAUDE.md` that points
  at `../../../docs/code-principles.md` and describes the delivered package clustering,
  the 500-line ceiling, the FQN rule, and the enforcement tests subtask 7
  added.
- For each section of `../../../docs/code-principles.md`, name the one or two places
  in this tree that now show the rule applied well, by path.
- Correct any rule the program proved wrong or too strong. A principle that
  could not be applied without harm is a principle to amend, not a violation
  to hide.
- Update `../../../runtime-kotlin/ARCHITECTURE.md` package-ownership paths that
  subtask 1 moved, without duplicating the principles doc.
- Record the boundary decisions this program produced — the capability
  vocabulary decision from subtask 3, the file-size ceiling, the FQN
  keep-list, and the not-mechanically-enforced list from subtask 7 — in the
  relevant area `../../../agent/decisions.md` files.
- Confirm existing documentation ledger / recovery-code tests still prove
  every documented recovery code resolves to source, including any code
  subtask 3 added.
- Run the full verification pass.

## Applicable Principles

- Where a document is the published contract, the document is authoritative
  and tests assert that code matches it.
- A pattern doc states the rule, the preferred shapes, the anti-patterns,
  then the reference examples to copy.
- Write direct, active prose. Remove filler, stale claims, and repetition.
- Update the validation section when the authoritative build or validation
  paths change.

## Acceptance Criteria

1. `../../../docs/code-principles.md` exists. Every section names at least one
   reference example in this tree, by path.
2. Any rule amended by this program says what changed and why, and no rule
   remains that the tree deliberately does not follow without that exception
   being stated.
3. `../../../AGENTS.md` describes the delivered package structure, the FQN rule, the
   500-line ceiling, and the enforcement subtask 7 added.
4. The capability decision, the file-size ceiling, the FQN keep-list, and
   the not-enforced list are recorded in area decision logs with dates.
5. `ARCHITECTURE.md` package-ownership sections match the post-clustering
   tree.
6. No documentation claims coverage, adapters, or tooling that does not
   exist.
7. `../../../scripts/validate` passes on a clean checkout.
8. Every acceptance criterion in the parent spec is satisfied, with any
   deviation stated explicitly rather than quietly dropped.

## Failure And Recovery Behavior

Not applicable. This subtask changes documentation and runs verification.

## Non-Goals

- Rewriting `../../../runtime-kotlin/ARCHITECTURE.md` as a style guide. It remains
  the hexagonal architecture record; principles live in
  `../../../docs/code-principles.md`.
- Adding new principles not exercised by this program. A rule with no
  reference example is a rule this tree has not yet earned.
- Copying v2 architecture-charter or slot-executioner docs that do not
  apply to this runtime.

## Dependency Notes

Runs last. Depends on every prior subtask, because the reference examples it
cites are the code those subtasks produce.

## Validation Strategy

`../../../scripts/validate` on a clean checkout, the documentation ledger / recovery
tests, and a read-through of the parent spec's acceptance criteria against
the delivered tree.

## Next Path

Program complete. Remaining structural debt, if any, is recorded as findings
for a future spec rather than left implicit.
