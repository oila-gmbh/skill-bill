---
status: Complete
---

# SKILL-71 Subtask 2 - Persisted Spec-Source Contract

Parent spec: [.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec.md](./spec.md)
Issue key: SKILL-71

## Scope

Add the durable, additive, back-compatible record of how a feature was prepared,
so subtasks 3 (writer) and 4 (readers) share one contract. The stamp — not the
config — is the per-feature source of truth.

- Decomposition manifest (`orchestration/contracts/decomposition-manifest-schema.yaml`):
  - add optional top-level `spec_source` enum `local | linear`, documented
    default `local`;
  - add optional per-subtask `linear_issue_id` (string or null), default null.
- Bump `contract_version` in lockstep: the schema `const` and
  `DECOMPOSITION_MANIFEST_CONTRACT_VERSION` in `DecompositionManifestSchemaPaths.kt`,
  in the same change, so `DecompositionManifestSchemaContractVersionTest` passes.
- Apply a runtime default for the new field via the coherence layer
  (`DecompositionManifestCoherenceValidator`) mirroring the existing
  `execution-model-default` precedent: when `spec_source` is absent, treat it as
  `local` before validation, so existing `0.2`-era manifests load and validate
  unchanged. Add a named `x-coherence-checks` entry documenting it.
- Keep `additionalProperties: false`: both new fields are declared properties, so
  the strict-object guarantee is preserved.
- single_spec: define the parsed `spec.md` convention (`spec_source: linear`
  line) and a small reader the spec readers use; absence means `local`. Reuse the
  line-parsing style already in `FileSystemFeatureTaskRuntimeRunInvariantsSource`
  (which parses `feature_size:` from the spec) rather than introducing a new
  parser shape.
- Surface `spec_source` / `linear_issue_id` through the domain manifest model and
  its read/write seams (`DecompositionSubtask`, manifest writer/reader) without
  changing `spec_path` semantics.

## Acceptance Criteria

1. The schema declares optional `spec_source` (`local | linear`, default `local`)
   and optional per-subtask `linear_issue_id` (nullable string), with
   `additionalProperties: false` preserved.
2. `contract_version` is bumped in both the schema and
   `DecompositionManifestSchemaPaths.kt` in lockstep;
   `DecompositionManifestSchemaContractVersionTest` passes.
3. A representative existing `0.2`-era manifest (no `spec_source`) loads,
   defaults to `local`, and validates without error under the bumped contract.
4. The single_spec `spec.md` `spec_source` line is parsed by the spec readers;
   absence resolves to `local`; an invalid value loud-fails.
5. The domain manifest model round-trips `spec_source` and per-subtask
   `linear_issue_id` through read and write without altering `spec_path`
   resolution or any other field.
6. No runtime phase-loop, handoff-payload, or schema-gate semantics change.

## Non-Goals

- No writer that sets these fields (subtask 3) and no consumer behavior (subtask
  4).
- No config reading (subtask 1) — the stamp is independent of config.
- No change to `spec_path` resolution or the working-tree read mechanism.
- Do not make either new field required.

## Dependency Notes

None structurally. Ordered after subtask 1 on the shared branch; subtasks 3 and 4
depend on this contract.

## Validation Strategy

- Schema/parity tests: contract-version lockstep; back-compat load of a `0.2`-era
  manifest; round-trip of both new fields; invalid-enum loud-fail.
- single_spec reader tests: present/absent/invalid `spec_source` line.
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate`.

## Next Path

Proceed to subtask 3:
`.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec_subtask_3_feature-spec-linear-mode.md`
