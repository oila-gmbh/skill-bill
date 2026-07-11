---
name: bill-python-code-check
description: Run Python project quality checks using the repository's configured tools, including pytest/unittest, ruff, mypy/pyright, tox/nox, uv, Poetry, pip, and pre-commit when present.
internal-for: bill-code-check
---

# Python Quality Check

## Purpose

Discover and run the repository's authoritative Python checks in safe dependency order. Preserve the configured environment manager, locked dependency state, supported Python matrix, framework lifecycle, generated boundaries, and caller-provided scope.

## Execution Steps

1. Determine the files in scope from `git diff --name-only` while preserving the caller's base branch; identify affected packages, applications, tests, migrations, workers, notebooks, generated contracts, and public interfaces before running commands.
2. Discover authoritative commands from build files, repository wrappers, and CI configuration, in that order, before falling back to defaults; inspect `pyproject.toml`, `tox.ini`, `noxfile.py`, `pytest.ini`, `setup.cfg`, workflow files, `Makefile`, `justfile`, containers, and framework entry points. Prefer `make check`, `just test`, or another repository wrapper when it owns environment or service setup.
3. Detect the package and environment owner from `uv.lock`, `poetry.lock`, `requirements*.txt`, pip-tools headers, `Pipfile.lock`, `pdm.lock`, `hatch.toml`, tox or nox configuration, and the `[build-system]` table. Record the supported Python matrix from CI and project metadata.
4. Require an already-provisioned environment, then run the pack's quality-check entrypoint through `.venv/bin/python` or a repository-owned manager invocation proven not to create or synchronize an environment, such as `uv run --frozen --no-sync`; use Poetry, PDM, Hatch, tox, or nox only when the repository's invocation explicitly reuses an existing environment without provisioning. If the locked environment or a documented non-syncing invocation is unavailable, report a blocker instead of installing dependencies, creating an environment, upgrading tools, or rewriting a lockfile.
5. Verify environment and metadata integrity first with the manager's read-only lock check, `python -m pip check` when pip owns the active environment, and repository package/import smoke commands; report mismatched Python or missing pinned tools as blockers.
6. Verify formatting without mutation through configured commands such as `ruff format --check`, `black --check`, or `python -m yapf --diff`; scope file-level commands to owned changed Python files when the repository supports it.
7. Run configured linting with `ruff check`, `flake8`, `pylint`, `pre-commit run --files`, or the repository wrapper; do not substitute an unconfigured linter or weaken its rules.
8. Run configured typing with `mypy`, `pyright`, or the authoritative tox or nox session after imports and generated stubs are valid; preserve strictness and the declared Python version matrix.
9. Run targeted `pytest path::test_name`, `python -m unittest`, or repository test selections first, including configured async tests; escalate to affected-package, integration, and full-suite commands when shared `conftest.py`, models, migrations, settings, public APIs, packaging, or worker contracts changed.
10. Build and inspect distributions with the configured backend through `python -m build`, Hatch, PDM, Poetry, or the repository wrapper; use `twine check dist/*` or owned wheel-install smoke tests when configured, and verify generated artifacts rather than committing them.
11. Run configured dependency and security checks such as `pip-audit`, `safety`, `bandit`, Semgrep, license policy, or lock verification only through repository-owned commands and existing environments; do not fetch a new scanner or mutate dependency state.
12. When detected, run framework and lifecycle validation such as `python manage.py check`, migration drift commands, FastAPI/OpenAPI generation checks, Flask application smoke checks, Celery configuration or worker tests, and notebook or generated-report validation.
13. Attribute every failure to scoped changed work, a pre-existing condition, generated output, or an environmental blocker. Record the command, evidence, owner, and residual risk; never report an unrun check as passing.
14. Apply the ordered fix strategy below, rerun the narrow failing command after each category, and escalate to the authoritative full suite after shared contracts or configuration change.

## Fix Strategy

Use this priority-ordered fix ladder so environment or package drift is understood before formatting churn and cheap failures stop expensive suites early. Never suppress a failure to make the checker pass.

1. Repair interpreter, environment, lock, import, package metadata, generated-stub, or framework configuration failures that prevent reliable analysis without changing the repository's supported versions.
2. Apply formatting only through the configured formatter and only to owned scoped files, then rerun its read-only verification command.
3. Fix lint and typing findings at their source; never add ignores, baselines, `# noqa`, or relaxed mypy or pyright settings merely to obtain a pass.
4. Repair deterministic unit and async behavior failures before integration tests, then fix database, service, worker, migration, and framework integration failures without replacing production semantics with mocks.
5. Repair build, wheel, sdist, entry-point, package-data, and generated-contract drift, inspecting the diff so build output or regenerated files are not accidentally committed.
6. Resolve configured dependency and security failures through repository-approved constraints or code changes; ask for direction when the fix changes dependency security posture or public compatibility.
7. Re-run targeted checks after each category and run the full suite when targeted checks cannot establish safety or shared code, fixtures, configuration, package exports, schemas, or lifecycle ownership changed.

Stop and report a blocker when credentials, external services, an unavailable supported interpreter, a missing repository-pinned tool, destructive migrations, or a maintainer decision prevents a required check. Preserve unrelated user changes and report pre-existing failures separately.
