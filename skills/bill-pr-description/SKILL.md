---
name: bill-pr-description
description: Use when generating a PR title, description, and QA steps from the current branch changes. Works standalone or as part of bill-feature-implement. Use when user mentions write PR description, generate PR, PR title, or create pull request.
---

# PR Description Generator

## Project Overrides

Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).

If `.agents/skill-overrides.md` exists in the project root and contains a matching section, read that section and apply it as the highest-priority instruction for this skill.

## How It Works

1. **Determine the comparison base** — use the branch this feature branch was created from when known, otherwise compute the best available merge-base from git context. Never assume `main`.
2. **Gather context** — read the git diff from that merge-base to `HEAD`, along with the commit log and branch name.
3. **Read project guidelines** — check `CLAUDE.md`, `AGENTS.md`, and the matching `bill-pr-description` section in `.agents/skill-overrides.md` when present.
4. **Search for a repo-native PR template** — this is mandatory before generating anything. Search ALL standard template locations listed below. Read any template file found. This step must produce either a found template or a confirmed absence.
5. **Generate** the title and description using the repo-native template if one was found, or the built-in fallback template only if no repo-native template exists.
6. **Present** the result to the user for review and adjustment.

## Repo-Native PR Template Search (mandatory)

You MUST search for a repo-native PR template before generating any description. Do not skip this step. Do not assume no template exists without checking.

Search these locations in order, using glob or file-read tools:

1. `.github/pull_request_template.md`
2. `.github/PULL_REQUEST_TEMPLATE.md`
3. `pull_request_template.md`
4. `PULL_REQUEST_TEMPLATE.md`
5. `.github/pull_request_template/*.md`
6. `.github/PULL_REQUEST_TEMPLATE/*.md`
7. `docs/pull_request_template.md`

When a repo-native template is found:

- **Use it as the output structure.** Preserve its headings, checklist items, section order, and any placeholder text exactly as authored.
- Do NOT reshape it into the built-in Skill Bill format.
- Fill the template sections with concise, reviewer-friendly content derived from the gathered git/spec context.
- If the template contains checklist items, keep them and check/uncheck as appropriate.

When multiple templates are found and there is no obvious default, ask the user which one to use.

Only when NO repo-native template is found at any of the above locations, fall back to the built-in Skill Bill template in the section below.

## PR Title

Short, under 70 characters, prefixed with the ticket ID if the branch name contains one (e.g., `feat: [ME-4493] Show empty state for daily report AI`).

## Built-in Fallback Template

**This template is a fallback.** Use it ONLY when no repo-native PR template was found in the search above.

```markdown
# Summary

<1-3 sentences: what changed and why. Reference the ticket/spec. Include motivation.>

<optional: bullet list of key changes if more than one logical change>

## Feature Flags

<flag name and description, or "N/A">

# How Has This Been Tested?

<overview of tests performed — unit tests, manual verification, preview checks>

<reproducible test instructions:>
1. <step>
2. <step>
3. <expected result>
```

## Rules

- Summary should explain the **why**, not just list files changed
- Test instructions should be concrete enough for a reviewer to reproduce
- If the feature is behind a flag, mention how to enable it for testing
- Keep it concise — reviewers appreciate brevity
- Always search for a repo-native PR template first — never skip the search step
- If invoked from `bill-feature-implement`, check `.feature-specs/<ISSUE_KEY>-<feature-name>/spec.md` for additional context (this file only exists when bill-feature-implement created it)
- If the caller provides an explicit comparison base or merge-base, use it instead of inferring one

## Telemetry

For the shared telemetry contract including the `orchestrated` flag semantics, follow [telemetry-contract.md](telemetry-contract.md).

This skill emits a single `skillbill_pr_description_generated` event via the `pr_description_generated` MCP tool.

**Standalone invocation:** after presenting the PR description (and after the user has created the PR, if applicable), call `pr_description_generated` with `commit_count`, `files_changed_count`, `was_edited_by_user` (true if the user requested changes to the generated description), `pr_created` (true if the PR has actually been created), and optional `pr_title`.

**Orchestrated invocation** (when called from `bill-feature-implement` or similar parent that passes `orchestrated=true`): call `pr_description_generated` with `orchestrated=true` and the same fields. The tool returns `{"mode": "orchestrated", "telemetry_payload": {...}}`. Return that payload to the orchestrator — it will embed it in its own finished event.
