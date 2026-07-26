# Feature: agent-config-review
Created: 2026-04-02
Status: In Progress
Sources: chat discussion for SKILL-3 about making Skill Bill able to review and quality-check its own repository type

## Acceptance Criteria
1. Add a first-class `agent-config` package under `skills/`.
2. Add `bill-agent-config-code-review` that reviews this repository type honestly instead of routing to Unknown/Unsupported.
3. Add `bill-agent-config-quality-check` and route quality-check requests for this repo type to it.
4. Update shared routers so repos dominated by skill/agent-config signals route to the new package.
5. Update validator, installer/docs/catalogs, and tests so the new package follows the existing governance contract fully.
6. Keep canonical base entry points stable while making Skill Bill able to review and validate its own repository type.

## Non-Goals
- Inventing a special bypass path outside the contract
- Adding unapproved taxonomy shapes
- Faking support by routing this repo type to an unrelated stack

## Open Questions
None

---

## Consolidated Spec

Add first-class support for Skill Bill's own repository type by introducing an `agent-config` package under `skills/`. This package should let the shared `bill-code-review` and `bill-quality-check` routers recognize skill/agent-configuration repositories and delegate to governed stack-specific implementations instead of returning Unknown/Unsupported.

The new package must follow the existing project contract completely. That means it should use the approved platform override naming shape, be recognized by the validator as an allowed package, be installed like other platform packages, and be represented in docs and tests just like existing supported stacks.

The first pass should include both `bill-agent-config-code-review` and `bill-agent-config-quality-check`.

The new review support should focus on the kinds of changes that dominate this repository: skill contracts, routing playbooks, installer/uninstaller behavior, override mechanics, validator rules, README/catalog drift, and similar agent-configuration infrastructure. The quality-check support should focus on the repository's existing validation commands and fix issues only in the current unit of work.

This feature should preserve the stable base entry points. Users should still enter through `bill-code-review` and `bill-quality-check`; the shared routers should simply gain the ability to classify this repository type and delegate to the new `agent-config` implementations.
