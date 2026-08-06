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

A scope that resolves to a real commit sequence — a commit range or an entire PR with more than one
commit — supports commit-focused delegated sequencing: the parent decides per-commit lane relevance
before launch, each specialist reviews one assembled bundle in a single pass, and one final
integration pass covers cross-commit behavior.

Every other scope — specific files, staged changes, unstaged changes, the combined working tree, an
exact supplied diff, or a single commit — has no commit sequence and therefore no cross-commit
behavior to integrate. Those scopes keep their existing semantics unchanged and report that
commit-focused delegated sequencing is not applicable, naming the resolved scope from the existing
`detected_scope` vocabulary. Never synthesize commit history for them to make the sequencing apply.
