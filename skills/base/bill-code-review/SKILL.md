---
name: bill-code-review
description: Use when you want a generic code-review entry point that detects the dominant stack in scope and delegates to the matching stack-specific review skill.
---

# Shared Code Review Router

Use this as the neutral review entry point for feature workflows and standalone reviews.

Keep this skill thin:
- detect the dominant stack in the review scope
- choose the matching stack-specific code-review skill
- pass through the same scope and relevant context
- do not duplicate stack-specific review heuristics here

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-code-review` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Setup

Determine the review scope:
- Specific files (list paths)
- Git commits (hashes/range)
- Working changes (`git diff`)
- Entire PR

Inspect both the changed files and repo markers before routing.

## Shared Stack Detection

Before routing, read `orchestration/stack-routing/PLAYBOOK.md`. Use it as the source of truth for:
- stack taxonomy
- signal collection order
- dominant-stack tie-breakers
- mixed-stack routing rules

Do not redefine stack signals here unless a route-specific exception is truly unique to code review.

## Routing Rules

- If `kmp` signals dominate, delegate to `bill-kmp-code-review`.
- If `backend-kotlin` signals dominate, delegate to `bill-backend-kotlin-code-review`.
- If `kotlin` signals dominate without meaningful `kmp` or `backend-kotlin` markers, delegate to `bill-kotlin-code-review`.
- If `php` signals dominate, delegate to `bill-php-code-review`.
- If `go` signals dominate, delegate to `bill-go-code-review`.
- If the review scope mixes `kmp` with other Kotlin-family scope, prefer `bill-kmp-code-review` because it layers the appropriate Kotlin-family baseline internally instead of running multiple Kotlin-family orchestrators side by side.
- If the review scope mixes `backend-kotlin` with generic `kotlin` but not `kmp`, prefer `bill-backend-kotlin-code-review` because it layers `bill-kotlin-code-review` internally instead of running both side by side.
- If another supported stack dominates, delegate to that stack's canonical `bill-<stack>-code-review` skill when it exists in the available skill catalog.
- If multiple supported stacks appear in one review scope, route to each matching stack-specific skill and merge the results.
- If the required stack-specific skill does not exist yet, say so explicitly and stop instead of pretending coverage exists.
- The delegated stack-specific skill is the source of truth for classification details, specialist selection, and review heuristics.

## Delegation Contract

When routing to another skill, pass along:
- the exact review scope
- the changed files or diff source
- the detected stack and key signals
- relevant `AGENTS.md` guidance and matching `.agents/skill-overrides.md` sections
- the rule that the delegated skill must follow its own `SKILL.md` as the primary rubric

Use parallel delegation only when multiple supported stacks are clearly in scope.

## Output Format

For a single delegated skill:

```text
Routed to: <skill-name>
Detected stack: <stack>
Signals: <markers>
Reason: <why this stack-specific reviewer was selected>

<delegated review output>
```

For multiple delegated skills:

```text
Routed to: <skill-a>, <skill-b>
Detected stack: Mixed
Signals: <markers>
Reason: <why multiple stack-specific reviewers were selected>

<merged delegated review output>
```

For unsupported stacks:

```text
Detected stack: Unknown/Unsupported
Signals: <markers>
Result: No matching stack-specific code-review skill is available yet.
```
