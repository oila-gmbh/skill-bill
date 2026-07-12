# Boundary History — platform-packs/python

## [2026-07-11] SKILL-114 python-pack-depth
Areas: platform-packs/python, runtime-kotlin/runtime-infra-fs tests
- Elevated all ten Python specialists to expert-depth, concrete-evidence guidance spanning language/runtime semantics, packaging, services/data, and Python-owned UI surfaces.
- Covered mutation and model semantics, package ownership, asyncio cancellation and task ownership, resources, concurrency/executors, workers, retries, shutdown, and observable failure paths.
- Strengthened API, persistence, security, testing, UI, and accessibility contracts for runtime validation, framework requests, ORM transactions/migrations, unsafe inputs and effects, pytest evidence, rendering, forms, keyboard/focus, semantics, feedback, and localization.
- Made tenant isolation evidence scope-aware: tenant-owned data needs trusted predicates or verified database/schema/session isolation that raw SQL, background work, and reused sessions cannot bypass. reusable
- Kept quality checks manager-aware and safely ordered across repository commands, format, lint, typing, tests, packaging, dependency/security, and conditional framework validation; pack and corresponding-rubric duplication gates pass. reusable
- Synchronized focused Python pack regression contracts with the completed guidance; no generated source, feature flag, or breaking runtime contract was introduced.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-07-10] SKILL-112 python-pack-specialist-parity
Areas: platform-packs/python, orchestration/review-orchestrator, runtime-kotlin/runtime-infra-fs tests
- Rebuilt all ten Python review specialists on the governed Focus/Ignore/Applicability/Project-Specific Rules skeleton while retaining Python-specific review substance and canonical severity closers.
- Made the audited Python reliability, security, persistence, performance, correctness, API, architecture, testing, UI, and accessibility concerns enforceable, including explicit cross-lane ignore boundaries.
- Aligned the Python baseline with whole-review specialist retention, vendored-path handling, deterministic wave batching, finding discipline, and evidence-preserving merge/dedup behavior. reusable
- Removed Python conformance exemptions, aligned its quality-check source with the governed skeleton, and documented label-independent bespoke focus metadata with direction-sensitive routing disambiguation. reusable
- Preserved pack frontmatter, contract versions, agent descriptions, manifest behavior, and authored/generated source boundaries; no feature flag or breaking runtime contract was introduced.
- Known limitation: local install staging remains stale until `./install.sh` can run after the active runtime workflow releases its continuation guard.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-05] SKILL-106 python-platform-pack
Areas: platform-packs/python, runtime-kotlin/scaffold, runtime-kotlin/runtime-infra-fs tests, README.md, docs
- Added shipped `python` pack: manifest-declared slug/display/shell contract, baseline review router, ten approved specialists, and default quality-check source under governed `content.md` only.
- Routing signals favor strong Python project markers (`pyproject.toml`, lockfiles, tox/pytest config, `*.py`) while documenting mixed-repo, generated, vendored, and tooling tie-breakers. reusable
- Followed the platform-pack extension pattern: manifest-driven discovery/install staging, no hard-coded routing bypass, no generated wrappers/pointers/native-agent outputs committed. reusable
- Added Python scaffold preset/test coverage for manifest load, malformed quality-check rejection, authored source shape, install-plan selection, and preset defaults.
- Known limitation: live `./install.sh`/install-apply sync is deferred because goal-continuation guard exits 64; install-plan validation and full repo checks passed.
Feature flag: N/A
Acceptance criteria: 8/8 implemented
