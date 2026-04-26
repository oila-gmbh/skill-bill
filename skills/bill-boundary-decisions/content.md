# Boundary Decisions Content

## Purpose

Record **why** something was done a certain way — not what changed (that belongs in `history.md`), but the reasoning behind non-obvious choices, special cases, constraints, and trade-offs that future contributors need to understand before modifying the code.

## Inputs Required

- Decision title (short, descriptive)
- Primary module/package/area where the decision applies
- User explanation of the decision (the agent captures and structures it, not invents it)

## Input Recovery

- If the user omits the primary boundary, infer it from the files most recently changed in the current diff.
- If the user gives a free-form explanation, the agent structures it into the entry format below — preserving the user's reasoning faithfully without adding interpretation.

## Entry Format

```markdown
## [<date>] <short decision title>
Context: <what situation or requirement prompted this decision — 1-2 lines>
Decision: <what was chosen — 1-2 lines>
Reason: <why this approach over alternatives — 1-3 lines>
```

Optional trailing lines (include only when relevant):

- `Alternatives considered: <what was rejected and why — 1 line>`
- `Revisit when: <condition that would make this decision worth re-evaluating>`

## Format Rules

- Max 10 lines per entry.
- Newest entry first (reverse chronological).
- No code snippets; describe patterns and choices in plain language.
- One decision per entry — if the user describes multiple decisions, write separate entries.

## File Rules

- File path: `<primary-boundary>/agent/decisions.md`.
- If the file does not exist, create it along with any missing parent directories.
- Newest entry first.
- No fixed entry cap.
- Keep older entries when they still provide useful context for understanding the boundary's design.
- Prune entries only when the decision has been fully reversed or the context no longer applies.

## Distinguishing from History

- `history.md`: *what* changed, reusable patterns, feature scope — written after feature completion.
- `decisions.md`: *why* something was done this way — written whenever the user wants to capture reasoning.

If the user describes something that is purely a "what changed" summary with no reasoning, suggest `bill-boundary-history` instead.

## Output

Report one concise result:

- Written (with entry count) or skipped (with reason).
- Target file path.
- Decision title(s) recorded.
