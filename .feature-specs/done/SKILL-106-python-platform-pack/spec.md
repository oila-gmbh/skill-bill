# SKILL-106 - Python Platform Pack

## Outcome

skill-bill supports Python as a first-class language platform through a full
manifest-declared `python` platform pack. Python projects should route through
the shared code-review and quality-check entry points, receive Python-specific
baseline and specialist review guidance, validate through the platform-pack
contract, and appear in install/docs surfaces through dynamic manifest
discovery.

## Scope

Add a new `platform-packs/python/` pack with:

- a valid `platform.yaml` using shell contract version `1.1`
- a baseline `bill-python-code-review` skill
- all approved code-review area specialists where Python has meaningful review
  guidance
- a default `bill-python-code-check` quality-check skill
- Python routing signals that identify normal Python applications, libraries,
  test suites, and package-managed projects without stealing mixed-stack repos
  from more specific platform packs
- manifest-declared generated pointers and add-on usage only where the platform
  contract supports them

The implementation should use existing scaffolder and pack patterns where
possible. If the scaffolder can produce the full pack shape, use it instead of
hand-assembling boilerplate. Authored source remains `content.md`; generated
`SKILL.md` wrappers and pointer files stay out of source.

## Acceptance Criteria

1. `platform-packs/python/platform.yaml` exists, validates against
   `orchestration/contracts/platform-pack-schema.yaml`, declares slug `python`,
   display name `Python`, shell contract version `1.1`, a baseline code-review
   entry, a default quality-check file, and Python-specific routing signals.
2. The Python routing signals include strong package and source markers such as
   `pyproject.toml`, `requirements.txt`, `setup.py`, `setup.cfg`, `Pipfile`,
   `poetry.lock`, `uv.lock`, `tox.ini`, `pytest.ini`, and `*.py`, with
   tie-breaker guidance for mixed repositories and generated or vendored Python
   files.
3. `bill-python-code-review` exists under
   `platform-packs/python/code-review/bill-python-code-review/content.md` with
   non-placeholder guidance, a specialist routing table, mixed-diff handling,
   and min/max specialist bounds matching the existing platform-pack review
   pattern.
4. Approved Python code-review specialists exist under
   `platform-packs/python/code-review/` with authored, non-TODO content for the
   approved areas that apply to Python: architecture, performance,
   platform-correctness, security, testing, api-contracts, persistence,
   reliability, ui, and ux-accessibility.
5. `bill-python-code-check` exists under
   `platform-packs/python/quality-check/bill-python-code-check/content.md` and
   gives practical Python validation guidance for common project managers and
   tooling, including pytest/unittest, ruff, mypy/pyright, tox/nox, uv, Poetry,
   pip, and pre-commit when present.
6. README and relevant user-facing docs/catalogs mention Python as a shipped
   platform pack without introducing hard-coded discovery behavior that bypasses
   platform manifests.
7. Tests or fixtures cover Python platform-pack discovery, manifest validation,
   scaffold or install-plan behavior affected by the new pack, and routing or
   validation rejection where a malformed Python pack would previously pass.
8. `skill-bill validate` passes, Python pack-specific validation passes, and
   render/install staging can process the Python pack without committing
   generated wrappers, generated support pointer files, or provider-specific
   native-agent output.

## Non-Goals

- Do not create legacy `skills/python/` platform overrides unless an existing
  pre-shell family explicitly requires them.
- Do not add Python as a hard-coded special case in routing, install, desktop,
  or validation paths when the manifest contract can drive the behavior.
- Do not add new code-review area names outside the approved taxonomy.
- Do not support every Python framework in depth; focus on broadly applicable
  Python package, application, library, API, data, and test-review guidance.

## Constraints

- Source skill directories under the platform pack contain `content.md` only,
  except for allowed `native-agents/` sources.
- Generated `SKILL.md` wrappers, support pointers, and provider-specific agent
  outputs are not committed.
- Any schema or runtime contract changes fail loudly through typed errors and
  include parity coverage when required.
- After source skill, renderer, support pointer, or install-staging changes,
  run `./install.sh` so the local install reflects the new staging hash.

## Validation Strategy

Run the project validation commands appropriate to the changed surface:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

At minimum, also run targeted Python platform validation/render commands for
`bill-python-code-review`, its specialists, and `bill-python-code-check` before
the full suite when iterating.
