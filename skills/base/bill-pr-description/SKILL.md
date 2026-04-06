---
name: bill-pr-description
description: Use when generating a PR title, description, and QA steps from the current branch changes. Works standalone or as part of bill-feature-implement. Use when user mentions write PR description, generate PR, PR title, or create pull request.
---

# PR Description Generator

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-pr-description` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

Generate a PR title, description, and QA/test steps ready to paste. Present the result to the user for review.

## How It Works

1. **Determine the comparison base** — use the branch this feature branch was created from when known, otherwise compute the best available merge-base from git context. Never assume `main`.
2. **Gather context** — read the git diff from that merge-base to `HEAD`, along with the commit log and branch name
3. **Read project guidelines** — check `CLAUDE.md`, `AGENTS.md`, and the matching `bill-pr-description` section in `.agents/skill-overrides.md` when present
4. **Prefer a repo-native PR template when available** — check standard repository template locations before falling back to the built-in template
5. **Generate** the title and description using the selected template
6. **Present** the result to the user for review and adjustment

## Repo-Native PR Template Preference

Before using the built-in Skill Bill template, inspect the target repository for PR templates in these standard locations:

1. `.github/pull_request_template.md`
2. `.github/PULL_REQUEST_TEMPLATE.md`
3. `pull_request_template.md`
4. `PULL_REQUEST_TEMPLATE.md`
5. `.github/pull_request_template/*.md`
6. `.github/PULL_REQUEST_TEMPLATE/*.md`

Apply the following rules:

- If exactly one template exists in the default single-file locations above, use it.
- If no default single-file template exists but exactly one directory-based template exists, use it.
- If multiple templates exist and there is no obvious default, do not guess silently; ask the user which template to use.
- If no repo-native template exists, fall back to the built-in Skill Bill template below.
- When using a repo-native template, preserve its headings, checklist structure, and section order rather than reshaping it into the Skill Bill fallback format.
- Still use the gathered git/spec context to fill the chosen template with concise, reviewer-friendly content.

## PR Title

Short, under 70 characters, prefixed with the ticket ID if the branch name contains one (e.g., `feat: [ME-4493] Show empty state for daily report AI`).

## PR Description Template

Use this exact template, filling in the sections:

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
- Use this built-in template only when no repo-native PR template is available
- If invoked from `bill-feature-implement`, check `.feature-specs/<feature-name>/spec.md` for additional context (this file only exists when bill-feature-implement created it)
- If the caller provides an explicit comparison base or merge-base, use it instead of inferring one
