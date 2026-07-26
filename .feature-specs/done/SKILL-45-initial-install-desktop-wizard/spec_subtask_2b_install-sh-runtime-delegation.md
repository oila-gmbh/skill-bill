# SKILL-45 Subtask 2b: install.sh Runtime Delegation

Status: Complete

Parent spec: [spec.md](./spec.md)

Source subtask being decomposed: [spec_subtask_2_cli-install-sh-migration.md](./spec_subtask_2_cli-install-sh-migration.md)

## Scope

Migrate `install.sh` from owning the full install algorithm to collecting existing user choices and delegating install planning/application to the stable runtime CLI entrypoints from Subtask 2a. Keep current install flows usable while removing shell-owned platform discovery, skill selection, link loops, native-agent loops, MCP registration loops, and telemetry mutation where those behaviors now exist in the shared runtime path.

Likely files/boundaries:

- `install.sh`
- CLI launcher/build scripts that package or invoke `runtime-cli`
- `runtime-kotlin/runtime-cli/src/main/kotlin/**` only for small compatibility adjustments discovered while wiring shell calls
- `runtime-kotlin/runtime-cli/src/test/kotlin/**` only for wrapper-facing CLI behavior

## Acceptance Criteria

1. `install.sh` remains usable for existing install flows and delegates install planning/application to the reusable runtime path instead of owning the full install algorithm.
2. Existing agent auto-detection and manual agent selection paths pass `copilot`, `claude`, `codex`, `opencode`, and `junie` choices to runtime CLI entrypoints.
3. Platform pack choices are passed to the runtime path so packs are discovered dynamically from `platform-packs` and base skills are always included by runtime planning.
4. Telemetry prompts preserve `anonymous`, `full`, and `off` choices and pass them through the typed runtime contract.
5. MCP registration behavior is reached through runtime plan/apply request choices rather than separate shell-owned registration loops.
6. The install flow continues using `~/.skill-bill/installed-skills` staging through runtime-owned generated-output handling.

## Non-Goals

- Do not reimplement runtime install planning or application in shell.
- Do not build desktop UI or package desktop installers.
- Do not change skill source shape, platform-pack manifest contracts, or governed staging rules.
- Do not commit generated governed artifacts or provider-specific native-agent output.

## Dependencies

Depends on Subtask 2a because `install.sh` needs stable runtime CLI plan/apply entrypoints before it can delegate reliably. It also depends on the packaged Kotlin installDist bins remaining executable for install-time bootstrap.

## Validation Strategy

Run focused shell delegation checks by exercising a non-destructive plan path or dry-run path where available, then run runtime CLI tests impacted by wrapper calls:

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:test)
```

Run `bill-quality-check` if install launcher behavior or shared runtime contracts are touched.

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_2b_install-sh-runtime-delegation.md`.
