# Feature: SKILL-54-pr-review-fix

Created: 2026-05-28
Status: In Progress
Issue key: SKILL-54
Originating doc: ZERO-86-pr-review-comment-orchestrator-skill (ZeroAccount repo)
Sources:
- ZERO-83 PR review handling workflow executed in a prior session
- Existing skill patterns (`gh-address-comments`, `bill-boundary-history`, `bill-quality-check` flow)

## Problem

The workflow for handling PR review comments is high-value but currently manual and session-dependent:

- Fetching unresolved review threads with correct file/line context
- Producing structured analysis (agree/disagree, hidden context, fix options, recommended default)
- Waiting for explicit user selection before editing
- Posting thread replies with strict style (`👍` for pure agreement; contextual text otherwise)
- Recording durable learnings
- Optionally creating out-of-scope follow-up specs
- Completing engineering hygiene (rebase, quality check, push)

This should be packaged as a reusable skill that works in a fresh session and different repo/project.

## Goal

Ship `bill-pr-review-fix`: a horizontal skill that orchestrates review-comment resolution from intake to verified push, with explicit approval gates and consistent output/response format.

## Acceptance Criteria

1. Skill can resolve target PR from current branch or explicit PR reference.
2. Skill fetches unresolved review threads with thread-aware metadata (resolved/outdated/path/line/comments) — must use GraphQL thread queries, not a flat comment list.
3. Skill outputs an analysis matrix per thread containing:
   - verdict (`agree` / `partial` / `disagree`)
   - rationale
   - hidden/special context
   - 2-3 fix options with one recommended default
   - proposed GitHub reply text
4. Skill pauses for user selection before making code changes.
5. When applying selected changes, skill keeps each edit traceable to specific thread IDs.
6. Skill posts replies to selected/all threads:
   - only `👍` when full agreement and no extra context
   - contextual response otherwise
7. For every agreed item, skill records a learning entry in the project's `agent/history.md` (or configured learning sink), nearest the changed module.
8. If a recommended fix is intentionally deferred as out-of-scope, skill creates a spec in `.feature-specs/` using the next available issue key. **Key detection is host-repo-aware**: scan `.feature-specs/` for the dominant `<PREFIX>-<N>` pattern and use the next number. Fallback to `SKILL-*` only if no pattern is detectable.
9. Skill runs quality gate (`bill-quality-check`, which auto-routes to stack-specific quality skill), fixes changed-file issues, and reports final status.
10. Skill supports optional final rebase and safe push (`--force-with-lease` when required).

## Non-Goals

- Auto-resolving GitHub threads without explicit user request
- Making architectural decisions without user confirmation when tradeoffs are significant
- Fixing unrelated pre-existing repo issues outside selected review scope

## Inputs

Required:
- PR identifier (number/URL) or current branch PR (auto-resolved)

Optional:
- Scope mode: `analyze-only` / `analyze+fix-selected` / `fix-all-unresolved`
- Reply mode: `draft-only` / `post-replies`
- Include hygiene: `rebase`, `quality-check`, `push`

## Output Contract

### Phase 1: Analysis Output

For each actionable thread:
- Thread ID + file/line
- Summary
- Verdict
- Context
- Options (recommended first)
- Proposed reply text

Plus:
- grouped list of non-actionable/resolved/outdated items
- explicit "waiting for user choice" checkpoint

### Phase 2: Execution Output

- Applied fixes mapped to thread IDs
- Posted/drafted replies mapped to thread IDs
- Learning entries written (path + bullets)
- Out-of-scope specs created (path + key)
- Verification results (commands + pass/fail)
- Git status summary and push result

## Workflow

1. Resolve PR context — determine PR from branch or explicit input
2. Fetch thread-aware comments via GraphQL
3. Classify threads — actionable / informational / already-resolved-or-outdated
4. Produce recommendation matrix
5. Approval gate — wait for user selection of threads/options
6. Implement selected fixes (traceability to thread IDs)
7. Run tests/checks according to stack (delegate to `bill-quality-check`)
8. Prepare/post thread replies per selected policy
9. Record learnings for agreed items into nearest `agent/history.md`
10. Create follow-up specs for deferred larger refactors (host-repo-aware key)
11. Optional rebase + safe push

## Learning Capture Rules

- Record only high-signal reusable lessons (not one-off patch details)
- Prefer boundary `agent/history.md` nearest the changed module
- Keep entries concise and future-actionable
- Delegate format/hygiene to `bill-boundary-history` when present

## Spec Generation Rules (Deferred Work)

- Detect next available issue key from `.feature-specs/` by scanning directory names for the dominant `<PREFIX>-<N>` pattern
- Create one spec per deferred major refactor/theme
- Include problem, scope, acceptance criteria, risks, and rollout plan

## Risks

- Misclassification of comments without full code context
- Over-fixing beyond approved scope
- Conflicting reviewer comments
- Unsafe force-push without lease

## Mitigations

- Explicit approval gate before edits
- Thread-to-change traceability
- Safe push policy (`--force-with-lease` only)
- Report assumptions and unresolved ambiguities clearly

## Test Plan

1. Dry-run on a PR with mixed comment types; verify matrix formatting and gating.
2. Apply selected fixes on a PR with at least one conflict; verify conflict handling path.
3. Validate `👍` vs contextual reply logic with fixtures.
4. Validate learning sink writes only for agreed threads.
5. Validate deferred spec creation uses correct next issue key per host repo.
6. Validate quality gate and push flows in success and failure scenarios.

## Rollout

Phase 1: Ship as manual-invoked skill in skill-bill; gather feedback on at least one real PR review session.
Phase 2: Add project-stack routing for quality checks and learning sink config (already covered by `bill-quality-check` delegation).
Phase 3: Standardize across repos with optional templates for output matrix and replies.

## Implementation Notes

- Skill is **horizontal** (cross-stack), authored at `skills/bill-pr-review-fix/content.md`.
- Scaffold via `skill-bill new` (not `create-and-fill`, since horizontal skills use `new`).
- After authoring, `skill-bill render` / install must sync the skill to all detected native agent directories (Claude, Codex, OpenCode, Copilot).
- Skill delegates quality checks to `bill-quality-check`, learning capture to `bill-boundary-history` when format consistency matters, and PR creation to `bill-pr-description` is **not** in scope (this skill operates on an existing PR).
