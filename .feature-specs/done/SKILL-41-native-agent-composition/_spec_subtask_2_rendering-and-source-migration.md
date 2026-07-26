# Subtask 2: rendering and source migration

Parent overview: `.feature-specs/SKILL-41-native-agent-composition/spec.md`

Status: Complete

## Scope

Use the composition model from subtask 1 during native-agent rendering and install. Composed native-agent output must expand the referenced `content.md` into the provider-native rendered files so installed Claude, Codex, OpenCode, and Junie agents remain self-contained in the native-agent cache and never depend on repo-local sidecars at runtime.

Migrate the direct Kotlin/KMP specialist native-agent source definitions that currently duplicate large specialist execution prose to the new composition directive where they have a corresponding governed `content.md`. Keep legitimate provider-neutral native-agent source files as source files; do not delete the `native-agents/*.md` definitions, do not check in provider-rendered artifacts, and do not revive generated governed `SKILL.md` source files.

Provider rendering should continue to live on `NativeAgentProvider` behavior and preserve strict LF-only deterministic rendering. Existing provider install locations should stay unchanged unless composition requires a minimal internal plumbing adjustment.

## Acceptance criteria

- Native-agent files remain source files and are not generated away or deleted.
- Direct specialist-native agents compose from their corresponding `content.md` without duplicating large execution prose.
- Installed provider-native agents are self-contained after composition and rendering.
- Composition behavior remains manifest-driven or explicitly declared and avoids platform shortlists.

## Non-goals

- Do not add unrelated native-agent provider features.
- Do not change provider-specific native-agent install locations unless required by composition.
- Do not create or commit generated provider-native render output.
- Do not modify unrelated skill authoring or scaffold behavior beyond what rendering needs.

## Dependencies

Depends on subtask 1 because rendering needs the parsed composition directive and manifest-driven target resolver before it can expand `content.md` safely.

## Validation strategy

Add focused rendering/install tests that assert composed native agents expand content into self-contained provider-native output and that no repo-local `content.md`, add-on, or shared contract path is required after install rendering. Update existing native-agent snapshots only where they represent the intended self-contained composed output. Run focused native-agent tests and `(cd runtime-kotlin && ./gradlew check)` if practical. Full repository validation is deferred to subtask 3.

## Recommended next prompt

Run bill-feature-implement on `.feature-specs/SKILL-41-native-agent-composition/spec_subtask_2_rendering-and-source-migration.md`.
