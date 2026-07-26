# SKILL-31 — Canonical SKILL.md shape across all skills

Status: Complete

Sources:
- Linear: SKILL-31
- Branch: feat/SKILL-31-canonical-skill-md-shape

## Problem
SKILL.md files in this repo have drifted from being scaffolds into being content dumps. Two workflow-runtime skills on this branch (bill-feature-implement, bill-feature-verify) duplicate step prose between SKILL.md and content.md, with the "source of truth" living in SKILL.md and content.md being the thinner stub. Other top-level skills under skills/* (bill-pr-description, bill-quality-check, bill-grill-plan, bill-boundary-decisions, etc.) have bespoke shapes embedding fallback templates, routing rules, and rule lists that should live in content.md.

The validators in scripts/validate_agent_configs.py actively pin many of these markers to SKILL.md (FEATURE_IMPLEMENT_SHELL_REQUIRED_MARKERS, validate_feature_implement_shell_contract, validate_feature_verify_shell_contract, WORKFLOW_DRIVEN_SKILLS marker lists). That's the root cause: the validator forced authors to put step prose, install gates, and embedded templates into SKILL.md.

Out-of-scope note: bill-editorial-assignment-desk lives on the unmerged feat/SKILL-30-editorial-workflows-readian branch and does not exist on main. Migrating it to the canonical shape is a SKILL-30 follow-up, not part of SKILL-31.

## Goal
Enforce a single canonical SKILL.md shape across every skill. Modeled on platform-packs/kmp/code-review/bill-kmp-code-review/SKILL.md. SKILL.md becomes a thin scaffold that points at content.md and shared ceremony files. All substantive prose lives in content.md.

## Canonical SKILL.md shape (locked)
- frontmatter: name (required), description (required). No other keys.
- ## Descriptor — required first body section. Lines: ``Governed skill: `NAME` ``, ``Family: `FAMILY` ``, ``Platform pack: `PACK` (PRETTY)`` (only when under platform-packs/), ``Area: `AREA` `` (only when family has subareas), `Description: ONE-LINER`.
- ## Execution — required second body section. Single line: `Follow the instructions in [content.md](content.md).`
- ## Ceremony — required third body section. Opens with `Follow the shell ceremony in [shell-ceremony.md](shell-ceremony.md).` then "When X applies, follow [FILE.md](FILE.md)." pointer lines from a fixed allowlist of supporting files. Short connective sentences (1-2) gluing pointers are allowed.

Section order is strict: Descriptor → Execution → Ceremony. No other H2 allowed. No H1 in body. No H3. No intro paragraph. Banlist enforced in body: fenced code, tables, ## Step N: headings, embedded templates, MCP install gates, telemetry instructions, routing rules, run-context placeholder lines (e.g. `Review session ID: ...`).

## Family taxonomy (4 total, no exceptions)
- code-review: bill-kotlin-code-review, bill-kotlin-code-review-architecture, bill-kotlin-code-review-api-contracts, bill-kotlin-code-review-performance, bill-kotlin-code-review-persistence, bill-kotlin-code-review-platform-correctness, bill-kotlin-code-review-reliability, bill-kotlin-code-review-security, bill-kotlin-code-review-testing, bill-kmp-code-review, bill-kmp-code-review-ui, bill-kmp-code-review-ux-accessibility
- quality-check: bill-kotlin-quality-check
- workflow: bill-feature-implement, bill-feature-verify (bill-editorial-assignment-desk lives on unmerged SKILL-30 branch and is excluded from this scope)
- advisor: bill-grill-plan, bill-boundary-decisions, bill-boundary-history, bill-pr-description, bill-create-skill, bill-skill-remove, bill-feature-guard, bill-feature-guard-cleanup, bill-unit-test-value-check, bill-code-review, bill-quality-check

Family is required on every SKILL.md. No path-based exceptions in the validator.

## Workflow contract surface (where step ids live)
The runtime authority for step ids is skill_bill/constants.py (e.g. FEATURE_IMPLEMENT_WORKFLOW_STEP_IDS). SKILL.md must NOT carry step ids or artifact name lists. The agent-facing binding `Step id: \`X\`` lives inline at each step heading in content.md, where step prose is. Validators that today pin "Step id: X" / artifact name markers to SKILL.md must be retargeted to content.md (and ideally cross-checked against constants.py).

## Project Overrides
Drop the per-skill `## Project Overrides` H2 from SKILL.md entirely. orchestration/shell-content-contract/shell-ceremony.md:16-27 already contains the .agents/skill-overrides.md rule. Skills inherit it via the shell-ceremony.md link in `## Ceremony`.

## Acceptance criteria
1. Canonical SKILL.md shape defined: frontmatter + ## Descriptor + ## Execution + ## Ceremony, in that order, modeled on bill-kmp-code-review/SKILL.md.
2. Banlist enforced in SKILL.md body: fenced code, tables, ## Step N: headings, embedded templates, MCP install gates, telemetry instructions, routing rules, run-context placeholder lines, H1, H3s, intro paragraphs.
3. Every SKILL.md in repo migrated to canonical shape (top-level skills under skills/* and platform-pack skills under platform-packs/**).
4. For workflow-runtime skills, step prose lives only in content.md with inline `Step id: \`X\`` bindings; canonical text consolidated from whichever file currently holds it.
5. Validators retargeted: FEATURE_IMPLEMENT_SHELL_REQUIRED_MARKERS, validate_feature_implement_shell_contract, validate_feature_verify_shell_contract, and validate_workflow_driven_skills check step/artifact/install-gate markers in content.md; step ids cross-checked against skill_bill/constants.py where applicable. The PROJECT_OVERRIDES_HEADING required-reference checks in validate_skill_file (scripts/validate_agent_configs.py:707-713) are replaced by the new validate_skill_md_shape so migrated skills/* SKILL.md files don't loud-fail the old rule. (Editorial validators are out of scope on this branch — see Problem.)
6. New validate_skill_md_shape function enforces strict allowlist on every SKILL.md (frontmatter keys, allowed sections, ordering, banlist).
7. bill-create-skill scaffold emits canonical shape for new skills.
8. All existing tests pass; new shape test passes; quality check clean.

## Non-goals
- Changing what skills do at runtime.
- Rewriting content.md prose beyond absorbing step text from SKILL.md.
- Modifying skill_bill/constants.py step ids/artifact names (already source of truth).
- Touching shared linked files (shell-ceremony.md, telemetry-contract.md, review-orchestrator.md) except where required for new validator behavior.
- Modifying installed ~/.claude copies (sync via existing mechanism).
