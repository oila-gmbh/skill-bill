# SKILL-41 Subtask 2: Authoring and Scaffold Commands

Parent spec: [spec.md](spec.md)

Depends on: [spec_subtask_1_foundation.md](spec_subtask_1_foundation.md)

## Scope

Update authoring, render, scaffold, fill, and edit command behavior so users work only with authored `content.md` sections while generated wrapper sections remain render-owned.

This subtask should build on the clean source/render boundary from subtask 1.

## Acceptance Criteria

1. `skill-bill render <skill>` produces deterministic generated wrappers for every governed skill, including `## Descriptor`, `## Execution`, and `## Ceremony`.
2. Scaffolding a new governed skill creates clean authored `content.md`, not wrapper-shaped source.
3. `skill-bill fill <skill-name>` writes authored content without requiring generated wrapper headings.
4. `skill-bill edit <skill-name> --section <heading>` targets authored content sections only.
5. If a user asks to edit `Descriptor`, `Execution`, or `Ceremony`, the CLI explains that those are generated wrapper sections and points to authored content or manifest fields instead.
6. Scaffold and authoring tests cover both accepted clean authored content and rejected generated wrapper headings.

## Non-Goals

- Do not implement native-agent composition in this subtask.
- Do not delete `native-agents/*.md`.
- Do not broaden platform-pack behavior beyond the clean authored-content scaffold contract.
- Do not write generated `SKILL.md` wrappers or platform pointer files into source.

## Dependencies

Subtask 1 must land first because these commands should reuse the same authored content parsing, wrapper rendering, and validation rules.

## Likely Files

- `runtime-kotlin/runtime-core/scaffold/**`
- `runtime-kotlin/runtime-core/authoring/**`
- `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/**`
- `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/**`
- `runtime-kotlin/runtime-core/src/test/**`
- `tests/fixtures/shell_content_contract/**`
- `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`
- `orchestration/shell-content-contract/PLAYBOOK.md`

## Validation Strategy

Run focused scaffold, CLI authoring, fill/edit, render determinism, and rollback tests. Then run:

```bash
(cd runtime-kotlin && ./gradlew check)
```

## Handoff Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-41-content-md-authored-surface/spec_subtask_2_authoring-scaffold.md`.
