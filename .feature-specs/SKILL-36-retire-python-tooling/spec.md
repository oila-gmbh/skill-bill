# SKILL-36 retire-python-tooling

Status: In Progress

## Sources

- Inline user design text provided for SKILL-36.
- Confirmed clarifications from the user:
  - Target outcome is no Python source/tooling left in the repo if feasible.
  - Delete `pyproject.toml` if feasible.
  - If planning determines the scope is too large, decompose into resumable subtasks and stop at the decomposition package.

## Acceptance Criteria

1. Port remaining `skill_bill/` bootstrap and maintainer behavior to Kotlin CLI/runtime commands.
2. Replace `install.sh` and `uninstall.sh` Python module calls and embedded Python config edits with Kotlin-backed commands or non-Python shell logic.
3. Remove Python console-script packaging from `pyproject.toml`, replacing it with packaged Kotlin bin shims as the install/runtime contract; delete `pyproject.toml` if feasible.
4. Port scaffold, shell-content-contract, install/link/unlink, upgrade, and validation helper behavior currently tested through Python modules to Kotlin APIs/CLI.
5. Migrate Python test coverage to Kotlin tests and/or shell/script integration tests that exercise the Kotlin implementation.
6. Migrate or retire remaining repo scripts: `scripts/migrate_to_content_md.py`, `scripts/validate_agent_configs.py`, `scripts/validate_release_ref.py`, and `scripts/skill_repo_contracts.py`.
7. Delete the Python package once no install, validation, script, or test path imports or executes it.
8. Update docs, installer messages, tests, and validation commands so the repo no longer presents Python as required tooling.
9. Preserve governed contracts: manifest-driven routing, shell contract loud-fails, scaffold atomicity, install/link behavior, and telemetry/workflow behavior.

## Non-Goals

- Reworking Skill Bill's feature set beyond the migration.
- Changing shell contract version unless the migration requires a contract change.
- Removing historical documentation references where they are clearly historical, unless they confuse current install/runtime behavior.

## Consolidated Spec

SKILL-36 we want to retire python tooling and migrate to kotlin fully. We can migrate them to Kotlin. It is not impossible. We just did not do that under "Python runtime retirement."

Current state:

- Retired: Python as the normal skill-bill / skill-bill-mcp runtime.
- Still present: Python as bootstrap + maintainer tooling.
- Can migrate: most or all of those remaining Python files, but it is a separate cleanup/migration project.

The hard part is not the code itself. It is the bootstrap contract:

- pyproject.toml currently exposes skill-bill and skill-bill-mcp as Python console scripts.
- install.sh still uses python -m skill_bill install ... for some install/link operations.
- tests and validators still import Python modules like skill_bill.install, shell_content_contract, and scaffolding helpers.

A clean Kotlin migration would look like:

1. Move skill_bill/install.py behavior fully behind Kotlin CLI commands.
2. Move scaffold/content-contract helpers fully to Kotlin or decide they stay as repo-only validation tools.
3. Replace python -m skill_bill install ... calls in install.sh / uninstall.sh.
4. Replace Python console-script packaging with direct packaged Kotlin bin shims.
5. Update tests to call Kotlin APIs/CLI instead of importing Python modules.
6. Delete the Python package once no install, validation, or test path depends on it.
