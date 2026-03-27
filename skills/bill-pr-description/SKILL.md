---
name: bill-pr-description
description: Use when generating a PR title, description, and QA steps from the current branch changes. Works standalone or as part of bill-feature-implement.
---

# PR Description Generator

Generate a PR title, description, and QA/test steps ready to paste. Present the result to the user for review.

## How It Works

1. **Determine the comparison base** — use the branch this feature branch was created from when known, otherwise compute the best available merge-base from git context. Never assume `main`.
2. **Gather context** — read the git diff from that merge-base to `HEAD`, along with the commit log and branch name
3. **Read project guidelines** — check `CLAUDE.md` / `AGENTS.md` at the project root for any PR conventions
4. **Generate** the title and description using the template below
5. **Present** the result to the user for review and adjustment

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
- If invoked from `bill-feature-implement`, check `.feature-specs/<feature-name>/spec.md` for additional context (this file only exists when bill-feature-implement created it)
- If the caller provides an explicit comparison base or merge-base, use it instead of inferring one
