---
name: review-orchestrator
description: Internal playbook for shared stack-specific code-review orchestrator contracts, merge rules, output structure, and baseline review behavior.
---

# Shared Code Review Orchestrator Playbook

Use this playbook when a stack-specific `*-code-review` orchestrator needs a shared contract for spawned specialists and
a consistent final report shape.

This playbook is only for shared review-orchestration behavior. Keep stack-specific routing heuristics, signal tables,
layering examples, caller-specific notes, and final quality-check command choices in the calling skill.

## Project Guidance

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance when using this playbook.

This playbook supplements, but does not replace, each skill's own `## Project Overrides` section and per-skill override
precedence.

## Shared Contract For Every Specialist

- Scope: review only the changes in the current PR/unit of work — do not flag pre-existing issues in unchanged code
- Review only meaningful issues (bug, logic flaw, security risk, regression risk, architectural breakage)
- Flag newly introduced deprecated components, APIs, or patterns when a supported alternative exists, or when
  deprecated usage is broad in scope and not explicitly justified
- Ignore style, formatting, naming bikeshedding, and pure refactor preferences
- Evidence is mandatory: include `file:line` + short description
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Maximum 7 findings per specialist
- Include a minimal, concrete fix for each finding

### Required Finding Schema

```text
[SEVERITY] Area: Issue title
  Location: file:line
  Impact: Why it matters (1 sentence)
  Fix: Concrete fix (1-2 lines)
  Confidence: High/Medium/Low
```

## Orchestrator Merge Rules

1. Collect results from every selected review layer. If the orchestrator also runs a baseline review layer, merge those
   findings first.
2. If a specialist agent fails or returns no output, note it in the summary and continue with available results.
3. Deduplicate by root cause (same evidence or same failing behavior).
4. Keep highest severity/confidence when duplicates conflict.
5. Prioritize: Blocker > Major > Minor, then blast radius.
6. Produce one consolidated report.

## Shared Output Format

Stack-specific orchestrator skills should keep their own Section 1 summary example. The rest of the output should use
the shared structure below.

### 2. Risk Register

Format each issue as:

```text
[IMPACT_LEVEL] Area: Issue title
  Location: file:line
  Impact: Description
  Fix: Concrete action
  Confidence: High/Medium/Low
```

Impact levels: BLOCKER | MAJOR | MINOR

### 3. Action Items (Max 10, prioritized)

```text
1. [P0 BLOCKER] Fix issue (Effort: S, Impact: High)
2. [P1 MAJOR] Fix issue (Effort: M, Impact: Medium)
3. [P2 MINOR] Fix issue (Effort: S, Impact: Low)
```

Priority: P0 (blocker) | P1 (critical) | P2 (important) | P3 (nice-to-have)
Effort: S (<1h) | M (1-4h) | L (>4h)

### 4. Verdict

`Ship` | `Ship with fixes [list P0/P1 items]` | `Block until [list blockers]`

## Shared Implementation Baseline

- If invoked standalone, ask: **"Which item would you like me to fix?"**
- If invoked from another orchestration skill, do not pause for user selection unless the caller explicitly wants
  interactive triage
- Let the calling skill specify any stack-specific caller list and the final quality-check command or router

## Review Principles

- Changed code only: review what was added or modified in this PR — do not report issues in untouched code, even if it
  violates current rules
- Evidence-based: cite `file:line`
- Project-aware: each agent applies local `AGENTS.md` and required local docs
- Actionable: every issue must have a concrete fix
- Proportional: do not nitpick style when architecture, correctness, or security problems are more important
- No overoptimization: do not report negligible performance findings with no measurable user-facing or
  production-facing impact
- Honest: if unsure, say what context is missing
