---
name: review-orchestrator
description: Maintainer-facing reference snapshot for shared stack-specific code-review orchestration contracts, merge rules, and output structure.
---

# Shared Code Review Orchestrator Snapshot

This maintainer-facing reference snapshot documents the shared review-orchestration contract used when authoring or updating installable skills.

Runtime-facing skills consume this contract through sibling supporting files such as `review-orchestrator.md` inside each skill directory. Do not reference this repo-relative path directly from installable skills.

## Shared Contract For Every Specialist

- Review only changed code in the current PR or unit of work
- Surface only meaningful issues such as bugs, logic flaws, security risks, regression risks, or architectural breakage
- Flag newly introduced deprecated APIs or patterns when a supported alternative exists, or when deprecated usage is broad and unjustified
- Ignore style-only nits, formatting preferences, and naming bikeshedding
- Evidence is mandatory: include `file:line` and a short description
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Keep each specialist review pass to at most 7 findings
- Include a minimal concrete fix for each finding

## Delegation Portability Contract

- Use the runtime's available delegation mechanism for parallel specialist review passes when delegation is supported and permitted
- Shared installable skills must not hardcode agent-specific delegation tool names such as `task` or `spawn_agent`
- If delegation is unavailable, perform the same specialist review passes inline and say so in the summary
- If a specialist review pass fails or returns no output, note it in the summary and continue with available results
- When multiple review passes produce overlapping findings, deduplicate by root cause and keep the highest severity/confidence version
- Prioritize final findings as `Blocker > Major > Minor`, then by blast radius

## Shared Report Structure

After Section 1 in a stack-specific review skill, use:

- `### 2. Risk Register`
- `### 3. Action Items (Max 10, prioritized)`
- `### 4. Verdict`
