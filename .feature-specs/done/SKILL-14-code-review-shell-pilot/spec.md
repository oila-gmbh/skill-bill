# Code Review Shell Pilot

- Issue key: SKILL-14
- Date: 2026-04-16
- Status: In Progress
- Source: inline user briefing
- Feature size: LARGE
- Rollout needed: false (no feature flag)

## Goal

Pilot the shell+content architectural split on `bill-code-review`. Extract `bill-code-review` into (1) a governed *shell* that owns ceremony, orchestration, output structure, telemetry, and contract enforcement, and (2) a user-owned *content* per platform that carries platform-specific review reasoning. The existing Kotlin/KMP/backend-kotlin/PHP/Go code-review content becomes the first consumer of the new shell as an *example pack* (relocated, not deleted).

## Why

Skill-bill is already more infrastructure than content (4.9k LOC Python, 4 orchestration playbooks, validator, MCP server, telemetry). Making the platform depth user-owned turns the repo from a prompt collection into a governed shell that teams adopt and extend. This PR is the pilot — the contract produced here will govern `bill-quality-check`, `bill-feature-implement`, `bill-feature-verify` in later PRs.

## Architectural decisions locked

- Relocate current platform code-review content to `platform-packs/<platform>/code-review/` in-repo (no sister repo yet).
- Agent-config pack conforms to the new contract as a platform pack (acts as a test case that the contract is general enough).
- **Full manifest-driven stack routing.** No hardcoded platform names in `orchestration/stack-routing/PLAYBOOK.md` or in `scripts/validate_agent_configs.py`. Platforms are discovered by scanning `platform-packs/*/platform.yaml`.
- Contract format: YAML `platform.yaml` per platform pack + markdown content files.
- Backup tag `v0.x-pre-shell-split` is already created on `main` (pre-relocation state is permanently recoverable).

## Acceptance Criteria (contract)

1. **Shell extracted.** `skills/base/bill-code-review/SKILL.md` becomes a platform-independent shell owning: routing, telemetry ceremony, output structure (Summary / Risk Register / Action Items / Verdict), severity/confidence scales, execution-mode reporting, learnings application, and contract-enforcement rules.
2. **Content contract defined.** A versioned schema specifies what each platform's code-review content package must provide: file names, required sections, declared specialist areas, routing signals. Schema lives at a canonical path (recommend `orchestration/shell-content-contract/PLAYBOOK.md`) and is referenced from the shell via a sibling sidecar.
3. **Contract versioning.** The shell declares `contract_version: 1` (or similar). Platform packs declare the contract version they target. Shell fails loudly on mismatch with a clear migration message.
4. **Loud-fail loading.** When a platform's code-review content is missing a required file or section, the shell refuses to run and prints a specific error naming the missing artifact — not silent fallback, not generic drift.
5. **Per-platform manifest.** Each platform pack declares its code-review areas in `platform.yaml` (replacing the hardcoded `ALLOWED_AREAS` list in `scripts/validate_agent_configs.py`). Manifest lives at a canonical path inside the platform pack.
6. **Validator shift.** `scripts/validate_agent_configs.py` stops enforcing hardcoded area list for code-review. Instead it validates: (a) shell contract integrity, (b) each platform pack's manifest against the contract schema, (c) declared area files exist and have required sections.
7. **Existing Kotlin-family code-review content relocated.** Kotlin, KMP, backend-Kotlin code-review content moves to `platform-packs/<platform>/code-review/` and is refactored to conform to the new contract. PHP, Go, agent-config code-review content does the same.
8. **Example pack is loadable.** Installer can install the example pack so day-one users still get a working `/bill-code-review` for kotlin/kmp/backend-kotlin/php/go/agent-config after the split.
9. **Stack routing manifest-driven.** `orchestration/stack-routing/PLAYBOOK.md` is rewritten to platform-pack discovery — reads routing signals from each `platform-packs/*/platform.yaml`. No enumerated platform names survive in orchestration playbooks or the validator.
10. **Installer updated.** `install.sh` installs the shell always; platform example packs become selectable. Migration rules for renamed paths (`skills/<platform>/bill-<platform>-code-review*` → `platform-packs/<platform>/code-review/`) land in the same PR. Existing users running `./install.sh` after pulling still get a working code-review flow.
11. **Docs updated.** `README.md`, `AGENTS.md`, `docs/ROADMAP.md`, `docs/getting-started-for-teams.md` reflect the new model. Catalog counts stay accurate.
12. **Tests cover both paths.** `tests/` gains coverage for: acceptance (valid platform pack loads), rejection (missing file, bad contract version, invalid manifest, missing required section) fails with a named error. Both paths are first-class.
13. **Only `bill-code-review` is piloted.** `bill-quality-check`, `bill-feature-implement`, `bill-feature-verify` are NOT refactored in this PR. They remain working in current form; they will adopt the proven contract in follow-up PRs. Their existing references to platform skill names must still work.
14. **Horizontal skills untouched.** `bill-grill-plan`, `bill-boundary-decisions`, `bill-boundary-history`, `bill-pr-description`, `bill-new-skill-all-agents`, `bill-feature-guard*`, `bill-unit-test-value-check` remain fully authored and unmodified.

## Non-goals

- Refactoring `bill-quality-check`, `bill-feature-implement`, `bill-feature-verify` in this PR.
- Deleting any existing platform skill content — it is relocated, not deleted.
- Changing the MCP server, telemetry CLI, or learnings pipeline.
- Changing naming rules for horizontal skills.
- Creating a sister repo (`skill-bill-examples`).

## Validation strategy

Run via `bill-quality-check` (routes to `bill-agent-config-quality-check`):

```bash
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```
