---
name: bill-agent-config-code-review
description: Use when conducting a thorough code review for governed skill, prompt, and agent-configuration repositories. Focus on routing correctness, contract drift, installer safety, portability, and docs/tests/catalog consistency. Produces a structured review with risk register and prioritized action items.
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

Before classifying, read [stack-routing.md](stack-routing.md). Use it as the source of truth for `agent-config` signals and mixed-stack routing expectations.

Before selecting review depth or formatting the final report, read [review-orchestrator.md](review-orchestrator.md). Use it as the source of truth for the shared specialist review contract, merge rules, common output sections, and review principles used by stack-specific review orchestrators.

Before delegating review execution, read [review-delegation.md](review-delegation.md). Use it as the source of truth for agent-specific delegated execution.

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

## Review Output Format

### 1. Classification & Review Summary
```text
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: agent-config
Signals: SKILL.md, AGENTS.md, install.sh, orchestration/, validator tests
Execution mode: inline | delegated
Review focus: routing/contracts, installer/runtime safety, docs/tests/catalog consistency
Reason: skill/agent-config repository signals dominate, so the governed repo review layer was selected
```

For the shared risk register, action items, verdict format, merge rules, and review principles, follow [review-orchestrator.md](review-orchestrator.md).
