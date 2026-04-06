---
name: bill-agent-config-code-review
description: Use when conducting a thorough code review for governed skill, prompt, and agent-configuration repositories. Focus on routing correctness, contract drift, installer safety, portability, and docs/tests/catalog consistency. Produces a structured review with risk register and prioritized action items. Use when user mentions review skill config, review agent config, routing review, installer review, or skill repository review.
---

# Agent-Config Repository Review

You are an experienced maintainer reviewing a governed skill or agent-configuration repository.

This skill owns review depth for repositories where the primary unit of work is AI skill contracts, routing playbooks, installer/configuration wiring, and validation logic rather than application code in a single programming language.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-agent-config-code-review` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. Pass relevant project-wide guidance and matching per-skill overrides to every delegated or inline review pass.

## Setup

Determine the review scope:
- Specific files (list paths)
- Git commits (hashes/range)
- Staged changes (`git diff --cached`; index only)
- Unstaged changes (`git diff`; working tree only)
- Combined working tree (`git diff --cached` + `git diff`) only when the caller explicitly asks for all local changes
- Entire PR

Resolve the scope before reviewing. If the caller asks for staged changes, inspect only the staged diff and keep unstaged edits out of findings except for repo markers needed for classification.

Inspect both the changed files and repo markers for skill/agent-config signals.

## Additional Resources

- For shared stack-routing signals and tie-breakers, see [stack-routing.md](stack-routing.md).
- For shared review-orchestration rules, see [review-orchestrator.md](review-orchestrator.md).
- For agent-specific delegated review execution, see [review-delegation.md](review-delegation.md).

When the caller already passed the detected stack, skip reading [stack-routing.md](stack-routing.md). For standalone invocation, read it before classifying.

Before selecting review depth or formatting the final report, read [review-orchestrator.md](review-orchestrator.md) unless the caller already passed the shared review contract.

Before delegating review execution, read only your current runtime's section in [review-delegation.md](review-delegation.md).

## Review Focus

Review this scope against the kinds of failures that matter in governed skill repositories:

- routing correctness and stack-signal drift
- skill references, naming, and package-taxonomy violations
- runtime/supporting-file contract mismatches
- installer/uninstaller safety, migration handling, and alias behavior
- override precedence, fallback honesty, and unsupported-path clarity
- README/catalog/docs/test mismatch against actual repository behavior

Treat these review focus areas as the specialist review surfaces for this skill. Apply them directly in the chosen execution mode; this package does not need deeper `agent-config` review subskills yet.

## Execution Mode

Select `inline` or `delegated` using [review-orchestrator.md](review-orchestrator.md).

- Use `inline` only when the agent-config review scope stays small and low-risk under the shared execution-mode contract
- Use `delegated` when the diff is large, routing/installer/validation risk is high, multiple repository contracts are changing at once, or the safest choice is unclear

If execution mode is `inline`, review the scope directly in the current thread using the focus areas above and the shared specialist review contract in [review-orchestrator.md](review-orchestrator.md).

If execution mode is `delegated`, run this same review in delegated execution using [review-delegation.md](review-delegation.md). If delegated review is required for this scope but unavailable on the current runtime, stop and report that explicitly. Do not invent deeper nested `agent-config` review passes unless the package grows approved specializations later.

## Review Output

### 1. Summary

```text
Review session ID: <review-session-id>
Review run ID: <review-run-id>
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: <stack>
Signals: <markers>
Execution mode: inline | delegated
Applied learnings: none | <learning references>
Specialist reviews: <selected specialists>
Reason: <why these specialists were selected>
```

Every finding in `### 2. Risk Register` must use this exact bullet format (do NOT use markdown tables):

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Severity: `Blocker | Major | Minor`. Confidence: `High | Medium | Low`.

### Auto-Import

After producing the final review output, automatically import it into the local telemetry store so the review run and findings are recorded without manual intervention.

Call the `import_review` MCP tool:
- `review_text`: the complete review output (Section 1 through Section 4)

### Auto-Triage

After the user responds to the review findings and the agent has acted on each decision (applied fixes, skipped findings, etc.), record the triage decisions so the telemetry event fires.

Each finding gets one decision using its position number from the risk register:
- `fix` — the finding was accepted and the fix was applied
- `accept` — the finding was accepted but no code change was needed
- `skip` — the finding was intentionally skipped (append a reason after ` - `)
- `false_positive` — the finding was incorrect

Call the `triage_findings` MCP tool:
- `review_run_id`: the review run ID from the review output
- `decisions`: prefer a single structured selection string that fully resolves the review, e.g. `["fix=[1,3] reject=[2]"]`
- fallback: explicit numbered decisions still work, e.g. `["1 fix", "2 skip - intentional", "3 accept"]`

Skip auto-triage when the review produced no findings.

For action items, verdict format, merge rules, and review principles, follow [review-orchestrator.md](review-orchestrator.md).

### Implementation Mode Notes

- If invoked from `bill-feature-implement`, `bill-feature-verify`, or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.
- After all P0 and P1 items are resolved, run `bill-quality-check` as final verification when the project uses a routed quality-check path and this review is being run standalone.
