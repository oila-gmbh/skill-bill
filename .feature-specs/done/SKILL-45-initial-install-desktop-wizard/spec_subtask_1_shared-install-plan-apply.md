# SKILL-45 Subtask 1: Shared Install Plan/Apply Backend

Parent spec: [spec.md](./spec.md)

Status: Complete

## Scope

Create the reusable typed install planning and apply backend that both the CLI/install shell path and desktop app can call. This subtask owns the shared runtime contract and implementation only; it does not migrate `install.sh`, build desktop UI, or produce installer packages.

The implementation should extract the behavior currently concentrated in `install.sh` into runtime-owned plan/apply APIs without changing governed source shape. The plan/apply path must cover selected agents, selected platform packs, base skill inclusion, telemetry level, MCP registration intent, install staging under `~/.skill-bill/installed-skills`, native-agent output handling, runtime distribution location, cleanup, structured outcomes, and explicit Windows symlink/elevation decision points.

Likely files/boundaries:

- `runtime-kotlin/runtime-core/src/main/kotlin/**/install/**`
- `runtime-kotlin/runtime-core/src/main/kotlin/**/launcher/**`
- `runtime-kotlin/runtime-core/src/main/kotlin/**/nativeagent/**`
- `runtime-kotlin/runtime-core/src/main/kotlin/**/telemetry/**`
- `runtime-kotlin/runtime-core/src/test/kotlin/**/install/**`
- Any existing runtime-core install contracts used by `runtime-cli`

## Acceptance Criteria

1. Exposes a typed install plan API that accepts agent selection, platform pack selection, telemetry level, MCP registration choice, runtime distribution inputs, and installation target paths.
2. Exposes a typed install apply API that consumes the plan and returns structured success, warning, and failure outcomes without requiring callers to parse shell output.
3. Base skills are always included in the generated plan, while platform packs are discovered dynamically from `platform-packs`.
4. Supported agents are `copilot`, `claude`, `codex`, `opencode`, and `junie`; the shared layer supports both detection results and manual selection.
5. Telemetry choices are `anonymous`, `full`, and `off`, matching existing CLI behavior.
6. Install application uses the existing staging model under `~/.skill-bill/installed-skills` and does not write generated governed artifacts into source directories.
7. The plan/apply contract includes MCP registration intent and outcome data for later CLI and desktop adapters.
8. Windows symlink/elevation behavior is represented explicitly in the plan/apply outcomes, including clear fallback or failure states.
9. Tests cover install planning, apply behavior, platform selection, agent selection, telemetry configuration, staging paths, and Windows symlink/elevation branches.

## Non-Goals

- Do not migrate `install.sh` or runtime CLI commands in this subtask.
- Do not build the desktop setup wizard UI or desktop state model.
- Do not change platform-pack manifest contracts or skill authoring rules.
- Do not commit generated `SKILL.md`, support pointer files, or provider-specific native-agent outputs.
- Do not add a remote marketplace or remote pack discovery.
- Do not change the supported-agent set unless a runtime contract gap is discovered and documented.

## Dependencies

This is the foundation subtask and has no implementation dependency on later subtasks. Later subtasks must consume this contract instead of duplicating install behavior or scraping interactive output.

## Validation Strategy

Run the focused runtime tests added for install plan/apply, then run:

```bash
(cd runtime-kotlin && ./gradlew check)
```

If broader repository behavior is touched, also run `bill-quality-check`.

## Handoff Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-45-initial-install-desktop-wizard/spec_subtask_1_shared-install-plan-apply.md`.
