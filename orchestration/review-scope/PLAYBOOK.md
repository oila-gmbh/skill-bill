---
name: review-scope
description: Shared review-scope selection contract for baseline code-review entrypoints. Defines the supported scope shapes and the diff commands each shape maps to.
---

# Shared Review Scope Contract

Use this sidecar to resolve the review scope before routing or reviewing.

Supported review scopes:

- Specific files (list paths)
- Git commits (hashes/range)
- Staged changes (`git diff --cached`; index only)
- Unstaged changes (`git diff`; working tree only)
- Combined working tree (`git diff --cached` + `git diff`) only when the caller explicitly asks for all local changes
- Entire PR
