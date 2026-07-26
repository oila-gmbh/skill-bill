# SKILL-36: Repo Script Migration

Status: Complete

## Sources

- `.feature-specs/SKILL-36-retire-python-tooling/spec_subtask_3_repo-script-migration.md`
- Pre-planning briefing for feature `repo-script-migration`

## Scope

Migrate or retire the remaining standalone Python maintainer scripts and update repo validation/docs so current workflows no longer require Python. Cover `scripts/migrate_to_content_md.py`, `scripts/validate_agent_configs.py`, `scripts/validate_release_ref.py`, and `scripts/skill_repo_contracts.py`. Prefer Kotlin CLI/runtime commands for reusable behavior and shell or repo-native integration tests for command-line workflows.

## Acceptance Criteria

1. Each listed Python script is either replaced by a Kotlin-backed command/non-Python script with equivalent behavior or explicitly retired because its workflow is obsolete.
2. Existing Python test coverage for migration, agent config validation, release reference validation, and skill repo contract validation is migrated to Kotlin tests and/or shell/script integration tests.
3. README, AGENTS validation guidance, installer-facing docs, and any workflow docs now point at non-Python validation commands.
4. Current validation still preserves governed contracts: dynamic manifest-driven discovery, shell contract lockstep, add-on ownership/routing rules, agent target correctness, and release reference checks.
5. No current install, validation, or maintainer workflow presents Python as required tooling.

## Non-Goals

- Do not update `install.sh` or `uninstall.sh` unless a validation-command rename from subtask 2 must be reflected; installer cutover belongs to subtask 2.
- Do not delete `skill_bill/` or `pyproject.toml`; final removal belongs to subtask 4.
- Do not remove clearly historical Python references unless they confuse current runtime, install, or validation behavior.
- Do not rework Skill Bill feature behavior beyond migration parity.

## Dependencies

Depends on subtask 1 for Kotlin governed contract/scaffold APIs and should account for subtask 2 command names if installer validation docs reference them.

## Validation Strategy

Run `bill-quality-check`. Add targeted tests or integration checks for every migrated/retired script path and run the updated validation command set documented by this subtask.

## Recommended Next Prompt

Run bill-feature-implement on `.feature-specs/SKILL-36-repo-script-migration/spec.md`.
