# SKILL-41 Subtask 3: Native-Agent Composition

Parent spec: [spec.md](spec.md)

Depends on:

- [spec_subtask_1_foundation.md](spec_subtask_1_foundation.md)
- [spec_subtask_2_authoring-scaffold.md](spec_subtask_2_authoring-scaffold.md)

## Scope

Keep `native-agents/*.md` as provider-neutral source files while allowing direct specialist-native agents to compose their execution body from the corresponding governed `content.md`.

Installed provider-native agents must remain self-contained. Any composed `content.md` body or shared contract must be expanded before writing install-cache output.

## Acceptance Criteria

1. Native-agent files remain source files and are not deleted or generated away.
2. Direct specialist-native agents can compose from the corresponding `content.md` without duplicating large execution prose.
3. Installed provider-native agents remain self-contained after native-agent composition and rendering.
4. Native-agent validation rejects broken composition directives, missing target content, or output that depends on repo-local files at install time.
5. Composition behavior remains manifest-driven or explicitly declared; do not hard-code platform shortlists.

## Non-Goals

- Do not change provider-specific native-agent install locations unless required by composition.
- Do not implement unrelated native-agent provider features.
- Do not reintroduce generated governed `SKILL.md` source files.
- Do not remove legitimate provider-neutral native-agent source definitions.

## Dependencies

Subtask 1 establishes the authored `content.md` source boundary. Subtask 2 should land first if native-agent composition needs render or command integration that depends on authoring behavior.

## Likely Files

- `native-agents/*.md`
- `runtime-kotlin/runtime-core/nativeagent/**`
- `runtime-kotlin/runtime-core/install/**`
- `runtime-kotlin/runtime-cli/src/test/**`
- `runtime-kotlin/runtime-core/src/test/**/NativeAgent*`
- `runtime-kotlin/runtime-core/src/test/**/InstallStagingTest*`
- `scripts/validate_agent_configs`
- `orchestration/shell-content-contract/PLAYBOOK.md`

## Validation Strategy

Run focused native-agent parsing, composition, install-staging, and agent-config validation tests. Then run:

```bash
(cd runtime-kotlin && ./gradlew check)
scripts/validate_agent_configs
```

## Handoff Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-41-content-md-authored-surface/spec_subtask_3_native-agent-composition.md`.
