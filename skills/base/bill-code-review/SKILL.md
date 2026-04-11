---
name: bill-code-review
description: Use when you want a generic code-review entry point that detects the dominant stack in scope and delegates to the matching stack-specific review skill. Use when user mentions code review, review my changes, review this PR, review staged changes, or asks to review code.
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
- Staged changes (`git diff --cached`; index only)
- Unstaged changes (`git diff`; working tree only)
- Combined working tree (`git diff --cached` + `git diff`) only when the caller explicitly asks for all local changes
- Entire PR

Inspect both the changed files and repo markers before routing.

Resolve the scope before routing. If the caller asks for staged changes, route and review only the staged diff; do not let unstaged edits expand the findings beyond repo-marker stack detection.

## Local Review Learnings

The top-level router owns learnings resolution for the current review context.

- When a local learnings resolver is available, resolve active learnings for the current repo and routed review skill before final routed review execution.
- Routed and delegated reviewers should reuse applied learnings passed by the caller instead of re-resolving them independently.
- Apply only active learnings.
- Prefer more specific scopes in this order: `skill`, `repo`, `global`.
- Treat learnings as explicit context, not as hidden suppression rules.
- If no learnings can be resolved, report `Applied learnings: none`.
- Pass the applied learning references forward to routed review layers and report them in the review summary.

## Additional Resources

- For shared stack-routing signals and tie-breakers, see [stack-routing.md](stack-routing.md).
- For agent-specific delegated review execution, see [review-delegation.md](review-delegation.md).

## Shared Stack Detection

Before routing, read [stack-routing.md](stack-routing.md). Use it as the source of truth for:
- stack taxonomy
- signal collection order
- dominant-stack tie-breakers
- mixed-stack routing rules

This supporting file lives beside `SKILL.md`; keep the routing rules in this skill aligned with it.

Do not redefine stack signals here unless a route-specific exception is truly unique to code review.

## Routing Rules

- If `kmp` signals dominate, delegate to `bill-kmp-code-review`.
- If `backend-kotlin` signals dominate, delegate to `bill-backend-kotlin-code-review`.
- If `kotlin` signals dominate without meaningful `kmp` or `backend-kotlin` markers, delegate to `bill-kotlin-code-review`.
- If `agent-config` signals dominate, delegate to `bill-agent-config-code-review`.
- If `php` signals dominate, delegate to `bill-php-code-review`.
- If `go` signals dominate, delegate to `bill-go-code-review`.
- If the review scope mixes `kmp` with other Kotlin-family scope, prefer `bill-kmp-code-review` because it layers the appropriate Kotlin-family baseline internally instead of running multiple Kotlin-family orchestrators side by side.
- If the review scope mixes `backend-kotlin` with generic `kotlin` but not `kmp`, prefer `bill-backend-kotlin-code-review` because it layers `bill-kotlin-code-review` internally instead of running both side by side.
- If another supported stack dominates, delegate to that stack's canonical `bill-<stack>-code-review` skill when it exists in the available skill catalog.
- If multiple supported stacks appear in one review scope, route to each matching stack-specific skill and merge the results using delegated execution.
- If the required stack-specific skill does not exist yet, say so explicitly and stop instead of pretending coverage exists.
- The routed stack-specific skill is the source of truth for classification details, specialist selection, review heuristics, and single-stack execution mode.

## Execution Contract

For multi-stack delegated routing, read [review-delegation.md](review-delegation.md) (only your current runtime's section). Skip it for single-stack reviews — the routed skill handles its own delegation.

For a single routed stack-specific review skill:
- Let the routed stack-specific reviewer choose `inline` or `delegated` using its own `review-orchestrator.md` contract
- If the routed skill selects `inline`, run it inline in the current thread instead of spawning an extra routed worker just for indirection
- If the routed skill selects `delegated`, use `review-delegation.md` and pass along the routed skill file path plus the required review context

For multiple routed stack-specific review skills:
- Use delegated workers for each routed stack-specific review skill and merge the results in the parent review
- Use parallel delegated workers only when multiple supported stacks are clearly in scope
- If delegated review is required for the current scope and the runtime lacks a documented delegation path or cannot start the required worker(s), stop and report that delegated review is required for this scope but unavailable on the current runtime

When routing to another skill, pass along:
- the exact resolved review scope label
- the exact review scope
- the current `review_session_id` when one already exists
- the current `review_run_id` when one already exists
- the applicable active learnings for the current repo and routed review skill when they are available
- the changed files or diff source
- the detected stack and key signals
- relevant `AGENTS.md` guidance and matching `.agents/skill-overrides.md` sections
- the parent thread's model when the runtime supports delegated-worker model inheritance
- the delegated skill file path
- the rule that the delegated skill must follow its own `SKILL.md` as the primary rubric
- the delegated skill's `review-orchestrator.md` contract when the routed skill is a stack review orchestrator

## Output Format

Generate one review session id per top-level review using the format `rvs-<uuid4>` (e.g. `rvs-550e8400-e29b-41d4-a716-446655440000`). If a parent workflow already passed a `review_session_id`, reuse it instead of generating a new one.

Generate one review run id per routed review using the format `rvw-YYYYMMDD-HHMMSS-XXXX` where `XXXX` is a random 4-character alphanumeric suffix (e.g. `rvw-20260405-143022-b2e1`). If a parent workflow already passed a `review_run_id`, reuse it instead of generating a new one.

For a single routed skill:

```text
Routed to: <skill-name>
Review session ID: <review-session-id>
Review run ID: <review-run-id>
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: <stack>
Signals: <markers>
Execution mode: inline | delegated
Applied learnings: none | <learning references>
Reason: <why this stack-specific reviewer was selected and why this execution mode was used>

<review output>
```

For multiple delegated skills:

```text
Routed to: <skill-a>, <skill-b>
Review session ID: <review-session-id>
Review run ID: <review-run-id>
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: Mixed
Signals: <markers>
Execution mode: delegated
Applied learnings: none | <learning references>
Reason: <why multiple stack-specific reviewers were selected and why delegated routing was required>

<merged delegated review output>
```

For unsupported stacks:

```text
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: Unknown/Unsupported
Signals: <markers>
Result: No matching stack-specific code-review skill is available yet.
```

## Auto-Import

After producing the final review output, automatically import it into the local telemetry store so the review run and findings are recorded without manual intervention.

Call the `import_review` MCP tool:
- `review_text`: the complete review output (Section 1 through Section 4)

## Auto-Triage

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
