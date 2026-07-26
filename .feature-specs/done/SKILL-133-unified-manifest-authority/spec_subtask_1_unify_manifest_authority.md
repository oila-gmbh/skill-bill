# SKILL-133 Subtask 1: Unify Manifest Authority

Status: pending

Parent spec: `.feature-specs/SKILL-133-unified-manifest-authority/spec.md`

## Scope

Implement the complete SKILL-133 contract in one executable unit: make the
decomposition manifest the sole prepared-feature authority marker, make
feature-spec preparation always write a manifest with one or more subtasks,
route one-subtask features through the existing goal workflow, and update all
affected contracts, guidance, tests, generated installs, and documentation.

## Acceptance Criteria (this subtask)

1. `decomposition-manifest.yaml` is the sole prepared-feature authority marker, while a bare `spec.md` is intake that must pass through feature-spec preparation on a new run.
2. Continuation lookup remains authoritative and occurs before artifact preparation or discovery.
3. Feature-spec preparation always writes a parent spec, one or more executable subtask specs, and a schema-valid manifest through the shared preparation path.
4. The preparation writer accepts exactly one subtask, rejects zero subtasks, and retains all existing identity, ordering, dependency, required-field, and atomicity validation.
5. `bill-feature` dispatches every authoritative manifest through the goal sidecar, including manifests with exactly one subtask.
6. A one-subtask goal launches and completes exactly one child feature-task workflow while preserving confirmation, status, resume, commit, PR, cleanup, and telemetry behavior.
7. Existing multi-subtask manifests remain compatible, and existing bare specs can be consumed as preparation intake without losing their contract content.
8. Local and Linear spec-source behavior remains correct for one-subtask and multi-subtask manifests.
9. Authored skill sources and operator documentation describe the unified manifest invariant; generated source wrappers are not committed, and `./install.sh` refreshes installed staging after source changes.
10. Focused acceptance and rejection tests plus all repository validation gates in the parent spec pass.

## Non-Goals

- Changing feature-size estimation beyond removing artifact-shape authority from it.
- Fabricating extra subtasks for small work.
- Migrating nonterminal durable workflows automatically.
- Changing review, phase-agent, add-on, or feature-task phase semantics.
- Supporting nested decomposition.

## Dependency Notes

This is the only subtask and has no dependencies. It owns the complete change so
the artifact-shape, routing, runtime, and documentation updates land atomically.

## Validation Strategy

Run focused writer, router, goal-runner, continuation, and spec-source tests
during implementation. Then run `skill-bill validate`, the runtime Kotlin
`./gradlew check`, strict Agnix validation, and agent-config validation from the
repository root.

## Next Path

Run this subtask through the manifest-driven SKILL-133 goal workflow.
