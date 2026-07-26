---
status: In Progress
issue_key: SKILL-11
source: inline user message in feature-implement-agentic session
---

# SKILL-11: Extract telemetry contract into a shared sidecar

## Problem

Today, every telemeterable skill's SKILL.md (and in some cases reference.md) contains duplicated inline text describing the telemetry contract: which MCP tools to call, when to pass orchestrated=true, how to collect and forward telemetry_payload, triage ownership, and lifecycle rules.

Skills affected include (non-exhaustive — grep to confirm):
- base routers: bill-code-review, bill-quality-check
- base feature workflows: bill-feature-implement, bill-feature-implement-agentic, bill-feature-verify, bill-pr-description
- every platform code-review orchestrator: bill-kotlin-code-review, bill-backend-kotlin-code-review, bill-kmp-code-review, bill-php-code-review, bill-go-code-review, bill-agent-config-code-review
- platform quality-check skills under each platform package

This duplication causes three concrete problems:

1. **Silent drift.** Platform overrides can forget, modify, or omit telemetry instructions and still pass validation, because the validator only checks for the literal string orchestrated=true (validate_orchestrator_passthrough in scripts/validate_agent_configs.py).
2. **High reconciliation cost for forks.** Skill Bill is intentionally fork-first — adopters clone and edit. Today a fork that wants to adopt a future telemetry-contract improvement has to reconcile the change across N skill files.
3. **Maintainer cost for in-repo changes.** When the telemetry contract evolves, every emitting skill has to be hand-edited to match.

## Goal

Collapse the telemetry contract surface from "duplicated text in every emitting skill" to "one canonical sidecar referenced by every emitting skill" — using the exact pattern the repo already uses for three other shared contracts.

## Precedent in this repo

The repo has already solved this problem three times for other shared contracts:
- stack-routing.md — stack detection rules, referenced from code-review and quality-check skills
- review-delegation.md — delegated-review execution contract
- review-orchestrator.md — review orchestration contract

Each lives as a sibling supporting file next to SKILL.md in skills that need it. The skill body contains a thin reference line rather than embedding the rules. Maintainer-facing reference snapshots live in orchestration/ and stay in sync with the sibling sidecars.

Telemetry is the fourth shared contract that has not been extracted yet. This task applies the same pattern.

## Decision from the orchestrator

- One shared `telemetry-contract.md` file (not per-kind).
- Full extraction across all packages in a single PR.

## Proposed solution

### 1. Add telemetry-contract.md as a sibling sidecar in every telemeterable skill directory

The sidecar describes:
- the standalone-first contract and the orchestrated flag semantics (the existing "Session correlation" content in docs/review-telemetry.md)
- which MCP tools a skill of a given kind must call, with orchestrated=true rules
- the child_steps aggregation contract for orchestrators
- the "routers never emit" rule for bill-code-review / bill-quality-check
- the rule that delegated child reviews don't call import_review/triage_findings

Follow whatever the existing sidecar convention is in the repo (plain file per skill dir vs shared source — check how stack-routing.md is handled across skill directories before choosing).

### 2. Replace inline telemetry text in each emitting skill with a thin reference

In each affected SKILL.md (and reference.md where applicable), replace the existing "Telemetry Ownership," "Triage Ownership," "Telemetry: Record Started/Finished," and "Orchestration contract" blocks with one reference line, e.g.:

> For telemetry, follow telemetry-contract.md.

Skill-specific telemetry fields (e.g. feature-implement's feature_size, acceptance_criteria_count, etc.) stay in the skill file — only the shared contract text moves out.

### 3. Add a maintainer-facing snapshot under orchestration/

Mirror the pattern of orchestration/stack-routing/PLAYBOOK.md et al. — add orchestration/telemetry-contract/PLAYBOOK.md as the maintainer reference, kept in sync with the sidecar content. Per AGENTS.md, runtime skills must consume the sibling sidecar, not the orchestration/ snapshot.

### 4. Update the validator

In scripts/validate_agent_configs.py:
- Replace or supplement validate_orchestrator_passthrough (literal-string check for orchestrated=true) with a check that every telemeterable skill references telemetry-contract.md as a sibling sidecar.
- Add the new sidecar to whatever required-sidecar registry exists in scripts/skill_repo_contracts.py.
- The set of skills that must reference the telemetry sidecar should come from the same registry so it stays the single source of truth.

### 5. Update tests

- Extend tests/test_validate_agent_configs_e2e.py with accept/reject fixtures: a skill that references the sidecar passes; one that omits the reference fails; one that duplicates inline telemetry text but omits the reference fails (so drift can't re-accumulate).
- Update any test that currently asserts the literal orchestrated=true string in SKILL.md to assert the reference pattern instead.
- Contract test confirming every skill in the telemeterable set has a telemetry-contract.md sibling.

### 6. Update docs

- docs/review-telemetry.md — add a brief "Where this contract lives" section pointing at the sidecar + maintainer snapshot.
- README.md — only if it currently describes sibling sidecars; add telemetry to the list.
- AGENTS.md — add telemetry-contract to the list of shared sibling supporting files in the "Non-negotiable rules" section.

## Out of scope

- Changing the telemetry schema itself (events, fields, payload shape). This is a pure extraction — behavior must be identical before and after.
- Versioning the telemetry contract (useful later for fork sync, but not this change).
- Auto-detection of orchestrated mode. The current explicit-flag design stays; only the text location moves.
- Reducing the number of telemetry events or merging skill-specific telemetry fields.

## Acceptance criteria

1. Every currently-telemeterable skill has a telemetry-contract.md sibling (or follows whatever the repo's existing sidecar resolution convention is).
2. Every currently-telemeterable skill's SKILL.md / reference.md references the sidecar and contains no duplicated contract text.
3. Skill-specific telemetry fields remain in the skill file; only shared contract text moved.
4. orchestration/telemetry-contract/PLAYBOOK.md exists and matches the sidecar content.
5. Validator rejects skills in the telemeterable set that omit the sidecar reference.
6. Validator rejects skills that reintroduce inline telemetry contract text without the reference.
7. All existing tests pass. New accept/reject fixtures cover the rule.
8. docs/review-telemetry.md and AGENTS.md describe the new sidecar.
9. No behavioral change to emitted telemetry events (same events, same fields, same orchestrated semantics).

## Suggested approach order

1. Read AGENTS.md, docs/review-telemetry.md, scripts/skill_repo_contracts.py, scripts/validate_agent_configs.py, and one existing sidecar (stack-routing.md) end-to-end first — before any edits.
2. Confirm the set of telemeterable skills by grep-ing import_review, triage_findings, feature_implement_started, feature_implement_finished, feature_verify_started, feature_verify_finished, quality_check_started, quality_check_finished, pr_description_generated across skills/.
3. Draft telemetry-contract.md content once, then propagate.
4. Update the validator and tests before touching skill files, so the test suite drives the migration.
5. Migrate skill files one package at a time.
