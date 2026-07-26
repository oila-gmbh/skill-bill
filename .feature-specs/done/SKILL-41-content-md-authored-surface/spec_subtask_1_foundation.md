# SKILL-41 Subtask 1: Authored Content Foundation

Status: In Progress
Sources: Parent feature decomposition for SKILL-41 content-md authored surface

Parent spec: [spec.md](spec.md)

## Scope

Establish the source/render contract for governed skills:

- Treat governed `content.md` files as authored skill content only.
- Remove committed wrapper boilerplate from every governed source `content.md`.
- Update `ShellContentLoader` so it accepts clean authored content and loud-fails missing or invalid authored content.
- Update wrapper rendering so generated `SKILL.md` output still contains the governed `## Descriptor`, `## Execution`, and `## Ceremony` sections while placing authored content in the rendered execution body.
- Move validation to the correct boundary by rejecting wrapper boilerplate in source `content.md` while preserving generated wrapper expectations for render output.
- Update focused fixtures and tests for the loader, renderer, repository validator, and render snapshots.

Use existing manifest-driven discovery and loud-fail exception patterns. Do not hard-code platform names or silently fall back when manifests, declared content files, or required authored sections are invalid.

## Acceptance Criteria

1. Every governed `content.md` in the repo contains authored skill content only; no `## Descriptor`, `## Execution`, or `## Ceremony` wrapper sections remain.
2. Generated `SKILL.md` output still contains the governed wrapper shape: Descriptor, Execution, and Ceremony.
3. `ShellContentLoader` accepts clean authored `content.md` and loud-fails invalid or missing authored content.
4. Validation rejects wrapper boilerplate in source `content.md`.
5. Render snapshots and fixtures that exercise generated wrappers remain deterministic at the source/render boundary.

## Non-Goals

- Do not implement scaffold command behavior beyond what is necessary for tests to compile.
- Do not implement `skill-bill fill` or `skill-bill edit` behavior changes in this subtask.
- Do not implement native-agent composition or install changes in this subtask.
- Do not reintroduce committed generated governed `SKILL.md` files or platform pointer files.
- Do not weaken validation or add silent fallback paths.

## Dependencies

This is the first subtask and has no dependency on later work.

Later subtasks depend on this one because scaffold, fill/edit, render, and native-agent composition must all use the same clean-authored-content contract.

## Likely Files

- `skills/**/content.md`
- `platform-packs/**/content.md`
- `platform-packs/**/addons/*.md` only if governed content validation includes add-on authored content
- `runtime-kotlin/runtime-core/scaffold/AuthoringRender.kt`
- `runtime-kotlin/runtime-core/shellcontent/**`
- `runtime-kotlin/runtime-core/validation/**`
- `runtime-kotlin/tests/**/ShellContentLoaderParityTest*`
- `runtime-kotlin/tests/**/AuthoringRenderOutputTest*`
- `runtime-kotlin/tests/**/RepoValidationRuntimeTest*`
- `tests/fixtures/shell_content_contract/**`
- `orchestration/shell-content-contract/**` only if fixture or validator docs must describe the new source/render boundary

## Validation Strategy

Run the most focused Kotlin tests covering loader, renderer, repo validation, and snapshots if available. Then run:

```bash
(cd runtime-kotlin && ./gradlew check)
```

If the full check is too slow during iteration, still leave the branch in a state where it can pass the full command before moving to the next subtask.

## Handoff Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-41-content-md-authored-surface/spec_subtask_1_foundation.md`.
