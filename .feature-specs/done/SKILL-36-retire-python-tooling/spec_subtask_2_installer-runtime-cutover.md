# SKILL-36 Subtask 2: Installer and Runtime Cutover

Status: Complete

Parent spec: [spec.md](spec.md)

## Scope

Move install, link, unlink, launcher, MCP registration, and runtime packaging behavior off Python. Complete any missing Kotlin install/link/unlink primitives and public CLI commands needed by `install.sh` and `uninstall.sh`, including Codex/OpenCode agent path parity, native subagent link/unlink behavior, and config edits. Replace embedded Python config mutation in shell scripts with Kotlin-backed commands or non-Python shell logic. Replace the Python console-script install/runtime contract with packaged Kotlin `installDist` bin shims.

## Acceptance Criteria

1. `install.sh` no longer calls `python3 -m skill_bill`, creates `.venv`, imports `skill_bill.launcher`, or uses embedded Python for config edits.
2. `uninstall.sh` no longer imports or executes Python for MCP removal, agent target cleanup, or native subagent unlinking.
3. Kotlin CLI/runtime commands provide parity for install/link/unlink, agent path detection, launcher/MCP registration, and uninstall cleanup behavior previously owned by `skill_bill.install` and `skill_bill.launcher`.
4. `pyproject.toml` no longer exposes Python console scripts; package/install docs and scripts use packaged Kotlin bin shims as the runtime contract. Delete `pyproject.toml` in this subtask only if no remaining repo tooling still requires it.
5. Tests are migrated or added for installer/uninstaller behavior, agent discovery/path behavior, link/unlink behavior, MCP registration cleanup, and packaged bin shim expectations.
6. Installer messages no longer present Python as required tooling.

## Non-Goals

- Do not migrate scaffold/content-contract behavior unless a small installer-facing CLI helper is required; subtask 1 owns governed scaffold parity.
- Do not migrate the four standalone repo scripts under `scripts/`; subtask 3 owns them.
- Do not delete the Python package unless all remaining Python script/test/import paths are gone; subtask 4 owns final deletion.
- Do not introduce a feature flag; rollout is N/A.

## Dependencies

Depends on subtask 1 if installer tests or commands rely on governed Kotlin scaffold/content-contract behavior. It may otherwise proceed after confirming subtask 1 has not left Python as the only source for any installer-facing helper.

## Validation Strategy

Run `bill-quality-check`. Also run targeted shell/script integration tests for `install.sh` and `uninstall.sh` plus the relevant Gradle tests for runtime CLI install/link/unlink commands.

## Recommended Next Prompt

Run bill-feature-implement on .feature-specs/SKILL-36-retire-python-tooling/spec_subtask_2_installer-runtime-cutover.md.
