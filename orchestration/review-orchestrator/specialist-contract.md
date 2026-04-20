---
name: specialist-contract
description: Compact shared contract for delegated specialist review subagents. Contains only the rules and output format specialists need — orchestrator-only sections (scope, execution mode, learnings, delegation) are omitted to reduce per-subagent token cost.
---

# Shared Specialist Contract

This is the delegated-worker subset of the full review-orchestrator contract. Orchestrators read the full `review-orchestrator.md`; delegated specialist subagents read this file instead.

Do not reference this repo-relative path directly from installable skills — use the sibling symlink instead.

## Shared Contract For Every Specialist

- Review only changed code in the current PR or unit of work
- Surface only meaningful issues such as bugs, logic flaws, security risks, regression risks, or architectural breakage
- Flag newly introduced deprecated APIs or patterns when a supported alternative exists, or when deprecated usage is broad and unjustified
- Ignore style-only nits, formatting preferences, and naming bikeshedding
- Evidence is mandatory: include `file:line` and a short description
- Include the user-visible or externally observable consequence for each finding
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Keep each specialist review pass to at most 7 findings
- Include a minimal concrete fix for each finding

## Shared Report Structure

Section 1 summary must include `Review session ID: <review-session-id>`.
Section 1 summary must include `Review run ID: <review-run-id>`.
Section 1 summary must include `Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>`.
Section 1 summary must include `Execution mode: inline | delegated`.
Section 1 summary must include `Applied learnings: none | <learning references>`.

Generate one review session id per top-level review using the format `rvs-<uuid4>` (e.g. `rvs-550e8400-e29b-41d4-a716-446655440000`). If a parent reviewer already passed a `review_session_id` into a delegated or layered review, reuse it instead of generating a new one. Reuse that same session id across the summary, parent-review handoff, and any learnings-resolution workflow for the current review lifecycle.

Generate one review run id per concrete review output using the format `rvw-YYYYMMDD-HHMMSS-XXXX` where `XXXX` is a random 4-character alphanumeric suffix for uniqueness (e.g. `rvw-20260405-143022-b2e1`). If a parent reviewer already passed a `review_run_id` into a delegated or layered review, reuse it instead of generating a new one. Reuse that same run id across the summary, the risk register, and any parent-review handoff or follow-up feedback workflow for the current review output.

After Section 1 in a stack-specific review skill, use:

- `### 2. Risk Register`
- `### 3. Action Items (Max 10, prioritized)`
- `### 4. Verdict`

Every finding in `### 2. Risk Register` must use this exact machine-readable bullet format:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings. The bullet format above is required for downstream tooling (triage, telemetry, stats) to parse findings correctly.

- Severity must be one of: `Blocker`, `Major`, `Minor`
- Confidence must be one of: `High`, `Medium`, `Low`
- Finding ids must be unique within the current review run and stable enough for follow-up feedback or fix requests in the same workflow
- Assign finding ids sequentially in risk-register order using `F-001`, `F-002`, `F-003`, and so on
