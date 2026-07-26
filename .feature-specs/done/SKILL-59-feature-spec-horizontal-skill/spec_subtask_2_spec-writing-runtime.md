---
status: Complete
---

# SKILL-59 Subtask 2 - Spec Writing + Manifest Runtime

Parent spec: [.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec.md](./spec.md)
Issue key: SKILL-59

## Scope

Implement the file-writing side of the shared preparation path. For `single_spec`, write or update the parent `spec.md`, report the `bill-feature-implement` path, avoid creating `decomposition-manifest.yaml`, and reject mode conflicts. For `decomposed`, write the parent spec, two or more ordered subtask specs, and a `decomposition-manifest.yaml` that passes the existing decomposition-manifest schema and coherence checks.

Reuse the existing `DecompositionManifestWriter` and validator boundaries instead of reimplementing manifest serialization.

## Acceptance Criteria

1. `single_spec` writes or updates the parent `.feature-specs/<issue>-<feature>/spec.md`.
2. `single_spec` reports the path that should be passed to `bill-feature-implement`.
3. `single_spec` never creates `decomposition-manifest.yaml`.
4. Mode conflicts, such as a single-spec request beside an existing decomposition manifest, loud-fail.
5. `decomposed` writes the parent spec, at least two ordered subtask specs, and the decomposition manifest.
6. Subtask specs include scope, acceptance criteria, non-goals, dependency notes, validation strategy, and next path.
7. The manifest validates against `orchestration/contracts/decomposition-manifest-schema.yaml`.
8. A prepared decomposition is runnable by `skill-bill goal <issue_key>`.

## Non-Goals

- Do not add prose-to-spec synthesis to `skill-bill goal`.
- Do not duplicate existing feature-implement decomposition serialization logic.
- Do not force every request into decomposed mode.
- Do not author generated `SKILL.md` wrappers or generated support pointer files.

## Dependencies

Depends on subtask 1 because this subtask implements persistence for the shared preparation models and decisions.

## Validation Strategy

Run `bill-quality-check`. Add focused filesystem/application tests for single-spec writes, conflict rejection, decomposed writes, schema validation, and goal-runner import/readability of the prepared manifest.

## Next Path

After this subtask completes and is committed, run `bill-feature-implement` on `.feature-specs/SKILL-59-feature-spec-horizontal-skill/spec_subtask_3_skill-and-workflow-wiring.md`.

