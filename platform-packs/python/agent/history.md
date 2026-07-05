# Boundary History — platform-packs/python

## [2026-07-05] SKILL-106 python-platform-pack
Areas: platform-packs/python, runtime-kotlin/scaffold, runtime-kotlin/runtime-infra-fs tests, README.md, docs
- Added shipped `python` pack: manifest-declared slug/display/shell contract, baseline review router, ten approved specialists, and default quality-check source under governed `content.md` only.
- Routing signals favor strong Python project markers (`pyproject.toml`, lockfiles, tox/pytest config, `*.py`) while documenting mixed-repo, generated, vendored, and tooling tie-breakers. reusable
- Followed the platform-pack extension pattern: manifest-driven discovery/install staging, no hard-coded routing bypass, no generated wrappers/pointers/native-agent outputs committed. reusable
- Added Python scaffold preset/test coverage for manifest load, malformed quality-check rejection, authored source shape, install-plan selection, and preset defaults.
- Known limitation: live `./install.sh`/install-apply sync is deferred because goal-continuation guard exits 64; install-plan validation and full repo checks passed.
Feature flag: N/A
Acceptance criteria: 8/8 implemented
