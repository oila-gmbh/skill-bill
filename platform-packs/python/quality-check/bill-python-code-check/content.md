---
name: bill-python-code-check
description: Run Python project quality checks using the repository's configured tools, including pytest/unittest, ruff, mypy/pyright, tox/nox, uv, Poetry, pip, and pre-commit when present.
internal-for: bill-code-check
---

# Python Quality Check

## Check Flow

1. Determine changed files using `git diff --name-only` against the relevant base.
2. Discover the project's own quality commands before choosing Python defaults.
3. Run targeted checks for changed Python files when the repo supports them.
4. Escalate to the full relevant suite when shared modules, fixtures, configuration, packaging, migrations, or public contracts changed.
5. Categorize failures as formatting, lint, type checking, tests, packaging, environment, dependency, or security/audit issues.
6. Fix issues in changed files by addressing the root cause.
7. Re-run the commands that failed, then any broader command required by the project convention.

## Command Discovery

- Prefer repository scripts, wrappers, Makefile targets, justfile recipes, task runners, or CI-defined commands over bare tool invocations.
- Inspect `pyproject.toml`, `tox.ini`, `noxfile.py`, `pytest.ini`, `setup.cfg`, `setup.py`, CI workflow files, `Makefile`, and `justfile`.
- Respect the configured environment manager: `uv`, Poetry, pip/venv, pip-tools, hatch, pdm, tox, nox, containers, or project-specific scripts.
- Use `uv run`, `poetry run`, `tox`, `nox`, or the repo wrapper when that is how the project normally invokes Python tools.
- If a required tool is configured but unavailable in the current environment, report the missing command explicitly instead of silently skipping it.
- Do not install dependencies, upgrade tools, regenerate lockfiles, or reconfigure the toolchain unless the repository instructions make that a normal local workflow.

## Test Execution

- Run pytest through the configured command when `pytest`, `pytest.ini`, `pyproject.toml` pytest settings, or test files indicate pytest ownership.
- Run unittest through the configured command when the project uses `python -m unittest`, `unittest` discovery, or standard-library test suites.
- Prefer changed-file or changed-package tests first when supported, such as `pytest path/to/test_file.py`, but escalate when shared fixtures, conftest files, package exports, settings, database models, or public APIs changed.
- Include integration or migration tests when persistence, framework wiring, external-client adapters, or deployment contracts changed.

## Static Checks

- Ruff: run configured formatting and lint commands, commonly `ruff format --check`, `ruff format`, `ruff check`, or project wrappers.
- Type checking: run mypy or pyright when configured in `pyproject.toml`, `mypy.ini`, `setup.cfg`, `pyrightconfig.json`, CI, tox, nox, or scripts.
- Orchestration: use tox or nox sessions when they are the project-owned source of truth for checks.
- Environments: prefer uv, Poetry, pip/venv, containers, or wrappers according to the repo's existing instructions.
- Pre-commit: run `pre-commit run --files <changed-files>` or the repo wrapper when `.pre-commit-config.yaml` exists and the project uses it.

## Fix Strategy

- Never add ignores, suppressions, baselines, or loosened type settings as the default fix.
- Never rewrite lockfiles, generated files, or tool configuration simply to hide failures.
- Fix formatting before lint/type/test failures when the toolchain separates those steps.
- Preserve the project's supported Python versions and dependency constraints.
- Report unavailable project-required checks, environment blockers, and any command intentionally not run.
