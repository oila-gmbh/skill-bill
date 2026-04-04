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

## Shared Scope Contract

- Resolve the exact review source before routing, classifying, or selecting specialist review passes
- Supported scope labels are `staged changes`, `unstaged changes`, `working tree`, `commit range`, `PR diff`, and `files`
- When the caller asks for staged changes, inspect only the staged/index diff and treat unstaged working tree edits as out of scope, except for repo markers needed for stack detection
- When the caller asks for unstaged changes, inspect only the unstaged working tree diff and do not fold in staged-only hunks unless the caller explicitly asks for all local changes
- Use `working tree` only when the caller explicitly wants both staged and unstaged local changes reviewed together
- State the resolved scope in Section 1 as `Detected review scope: ...`

## Shared Execution Mode Contract

- Review skills must choose an execution mode of `inline` or `delegated` before running routed review layers or specialist review passes
- `inline` is allowed only when the review scope is small and low-risk; treat a scope as inline-eligible only when all of the following are true:
  - one routed stack-specific review skill is sufficient
  - the diff is small enough to inspect in one thread without losing coverage
  - no mixed-stack or mixed KMP/backend layering requires multiple routed review layers
  - no high-risk signals dominate the diff, such as auth/security/secrets changes, public API or schema changes, persistence or migration changes, concurrency/lifecycle/threading changes, retries/timeouts/caching/observability changes, rollout/feature-flag changes, or broad config/infra changes
- If any inline-eligibility condition is false or unclear, choose `delegated`
- Inline mode must still run the selected baseline or specialist review passes deliberately, using each reviewer's own rubric and the shared specialist contract; do not collapse the review into a generic skim

## Shared Learnings Context

- The top-level review caller owns learnings resolution for the current review context
- When applied learnings are already passed in by the caller, reuse them instead of re-resolving them independently in nested review layers
- For a top-level or standalone review, resolve active learnings for the current repo and routed review skill before running the review when a local learnings resolver is available
- Apply only active learnings; do not use disabled learnings as review context
- Prefer more specific scopes in this order: `skill`, `repo`, `global`
- Treat learnings as explicit context, not as hidden suppression rules; do not let them override evidence-based correctness, security, or contract findings
- If no learnings were passed in and no local resolver is available, report `Applied learnings: none` instead of inventing hidden context
- Pass the applied learnings forward to delegated or layered review passes when the current review routes additional workers

## Shared Delegation Contract

- Runtime-facing review skills must read `review-delegation.md` before delegating routed review layers or specialist review passes
- When execution mode is `delegated`, routed review layers and specialist review passes run as separate subagents on supported runtimes
- If delegated review is required for the current scope and a supported runtime cannot start the required workers, stop and report that delegated review is required for this scope but unavailable on the current runtime
- If a specialist review pass fails or returns no output, note it in the summary and continue with available results when the parent skill contract permits it
- When multiple review passes produce overlapping findings, deduplicate by root cause and keep the highest severity/confidence version
- Prioritize final findings as `Blocker > Major > Minor`, then by blast radius

## Shared Report Structure

Section 1 summary must include `Review session ID: <review-session-id>`.
Section 1 summary must include `Review run ID: <review-run-id>`.
Section 1 summary must include `Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>`.
Section 1 summary must include `Execution mode: inline | delegated`.
Section 1 summary must include `Applied learnings: none | <learning references>`.

Generate one review session id per top-level review using the format `rvs-YYYYMMDD-HHMMSS`. If a parent reviewer already passed a `review_session_id` into a delegated or layered review, reuse it instead of generating a new one. Reuse that same session id across the summary, follow-up telemetry, and any learnings-resolution workflow for the current review lifecycle.

Generate one review run id per concrete review output using the format `rvw-YYYYMMDD-HHMMSS`. If a parent reviewer already passed a `review_run_id` into a delegated or layered review, reuse it instead of generating a new one. Reuse that same run id across the summary, the risk register, and any follow-up feedback workflow for the current review output.

After Section 1 in a stack-specific review skill, use:

- `### 2. Risk Register`
- `### 3. Action Items (Max 10, prioritized)`
- `### 4. Verdict`

Every finding in `### 2. Risk Register` must use this machine-readable format:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Finding ids must be unique within the current review run and stable enough for follow-up feedback or fix requests in the same workflow.
Assign finding ids sequentially in risk-register order using `F-001`, `F-002`, `F-003`, and so on.
