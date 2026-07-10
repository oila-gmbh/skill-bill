---
name: bill-python-code-check
description: Run Python project quality checks using the repository's configured tools, including pytest/unittest, ruff, mypy/pyright, tox/nox, uv, Poetry, pip, and pre-commit when present.
internal-for: bill-code-check
---

# Python Quality Check

## Purpose

Validate Python changes with the repository's configured test, formatting, lint, type-checking, packaging, environment, dependency, and security tooling.

## Execution Steps

- Determine the files in scope using `git diff --name-only` against the relevant base.
- Discover commands from repository build files, wrappers, and CI configuration before falling back to defaults; inspect `pyproject.toml`, `tox.ini`, `noxfile.py`, `pytest.ini`, `setup.cfg`, `setup.py`, workflow files, `Makefile`, and `justfile`.
- Respect the configured environment manager: `uv`, Poetry, pip/venv, pip-tools, hatch, pdm, tox, nox, containers, or project-specific scripts.
- Run the pack's quality-check entrypoint through `uv run`, `poetry run`, tox, nox, a repository script, or the repository wrapper when it owns invocation.
- Run targeted checks for changed Python files first when supported, including pytest or unittest tests and configured Ruff, mypy, pyright, tox, nox, or pre-commit checks.
- Escalate when shared fixtures, `conftest` files, package exports, settings, database models, migrations, framework wiring, external-client adapters, packaging, configuration, or public APIs changed.
- Categorize failures as formatting, lint, type checking, tests, packaging, environment, dependency, or security/audit issues.
- If a required tool is configured but unavailable in the current environment, report the missing command explicitly instead of silently skipping it.
- Do not install dependencies, upgrade tools, regenerate lockfiles, or reconfigure the toolchain unless the repository instructions make that a normal local workflow.

## Fix Strategy

- Follow the priority-ordered fix ladder of structure, formatting, lint, types, behavior, and tests; never suppress failures with ignores, baselines, or loosened type settings.
- Never rewrite lockfiles, generated files, or tool configuration simply to hide failures.
- Fix issues in changed files at their root cause and preserve the project's supported Python versions and dependency constraints.
- Re-run targeted checks after fixes, then escalate to the full suite when targeted checks cannot establish safety.
- Report unavailable project-required checks, environment blockers, and any command intentionally not run.
