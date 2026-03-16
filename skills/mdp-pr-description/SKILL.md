---
name: mdp-pr-description
description: Generate a PR title, description, and QA steps from the current branch changes. Works standalone or as part of mdp-feature-implement.
---

# PR Description Generator

Generate a PR title, description, and QA/test steps ready to paste. Present the result to the user for review.

## How It Works

1. **Gather context** — read the git diff (`git diff main...HEAD`), commit log, and branch name
2. **Read project guidelines** — check `CLAUDE.md` / `AGENTS.md` at the project root for any PR conventions
3. **Generate** the title and description using the template below
4. **Present** the result to the user for review and adjustment

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
- If invoked from `mdp-feature-implement`, check `.feature-specs/<feature-name>/spec.md` for additional context (this file only exists when mdp-feature-implement created it)
