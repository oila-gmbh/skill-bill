# SKILL-41 Subtask 4: Final Validation and Repository Gates

Parent spec: [spec.md](spec.md)

Depends on:

- [spec_subtask_1_foundation.md](spec_subtask_1_foundation.md)
- [spec_subtask_2_authoring-scaffold.md](spec_subtask_2_authoring-scaffold.md)
- [spec_subtask_3_native-agent-composition.md](_spec_subtask_3_native-agent-composition.md)

## Scope

Audit the integrated SKILL-41 implementation against the parent acceptance criteria and run the full required repository gates.

This subtask should not introduce major new feature behavior unless a gap is found during audit. Prefer targeted fixes for missed acceptance criteria, docs drift, or validation failures.

## Acceptance Criteria

1. Every parent acceptance criterion from `spec.md` is mapped to implementation evidence.
2. Every governed `content.md` in the repo contains authored skill content only; no `## Descriptor`, `## Execution`, or `## Ceremony` wrapper sections remain.
3. Generated `SKILL.md` output still contains the governed wrapper shape: Descriptor, Execution, and Ceremony.
4. `ShellContentLoader` accepts clean authored `content.md` and loud-fails invalid or missing authored content.
5. Validation rejects reintroduced wrapper boilerplate in source `content.md`.
6. `skill-bill render <skill>` produces deterministic generated wrappers for every skill.
7. Scaffolding, fill, edit, native-agent composition, and install behavior satisfy the parent spec.
8. `(cd runtime-kotlin && ./gradlew check)` passes.
9. `npx --yes agnix --strict .` passes.
10. `scripts/validate_agent_configs` passes.

## Non-Goals

- Do not reopen the architecture of completed subtasks unless an acceptance criterion cannot be satisfied.
- Do not weaken validation to get gates green.
- Do not introduce generated governed `SKILL.md` files or platform pointer files into git.
- Do not delete `native-agents/*.md`.

## Dependencies

This runs last because it validates the integrated behavior across all prior subtasks.

## Likely Files

- Test and documentation files touched by subtasks 1 through 3
- `README.md`
- `AGENTS.md`
- `orchestration/shell-content-contract/**`
- `scripts/validate_agent_configs`

## Validation Strategy

Run all required gates:

```bash
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Also run targeted manual checks from the parent spec:

```bash
git ls-files '*SKILL.md'
runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli render <skill-id> --repo-root .
```

`git ls-files '*SKILL.md'` should continue to return no governed generated wrappers.

## Handoff Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-41-content-md-authored-surface/spec_subtask_4_final-validation.md`.
