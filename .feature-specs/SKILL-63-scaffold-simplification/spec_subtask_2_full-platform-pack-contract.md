---
status: Pending
---

# SKILL-63 Subtask 2 - Full Platform Pack Contract

Parent spec: [.feature-specs/SKILL-63-scaffold-simplification/spec.md](./spec.md)
Issue key: SKILL-63

## Scope

Make new platform-pack creation always generate the full governed pack. Remove starter/subset decisions from user-facing and payload-based creation.

This subtask owns the platform-pack request shape and generated output:

- full pack is the only new platform-pack scaffold mode;
- baseline code-review and default quality-check are always generated;
- every approved code-review specialist area is always generated and registered;
- `skeleton_mode` and `specialist_areas` are rejected for new platform-pack payloads.

## Acceptance Criteria

1. CLI platform-pack wizard does not prompt for skeleton mode.
2. CLI platform-pack wizard does not prompt for specialist areas.
3. Desktop scaffold wizard removes platform-pack skeleton/subset controls.
4. Desktop scaffold payload mapping emits a full-pack request shape without `skeleton_mode` or `specialist_areas`.
5. Runtime scaffold policy rejects explicit `skeleton_mode` with a clear migration message.
6. Runtime scaffold policy rejects explicit `specialist_areas` with a clear migration message.
7. Platform-pack scaffold output includes baseline code-review, default quality-check, and every approved specialist area.
8. Generated `platform.yaml` registers every generated specialist under declared areas and declared files.
9. Full-pack default specialist native-agent source generation remains intact: when subagents are not suppressed, the baseline orchestrator receives one provider-neutral source entry per generated specialist.
10. `no_subagents` remains supported and suppresses only native-agent source stubs, not pack content files or manifest area registration.
11. `subagent_specialists` remains supported only as an override for orchestrator subagent source names; it must not reduce generated specialist content files or manifest area registration.
12. Existing non-full platform packs remain valid if their manifests conform to the current platform-pack schema.
13. Documentation and scaffold payload examples show full-pack creation only and explain that unwanted focus areas can be removed afterward through governed removal paths.

## Non-Goals

- Do not force existing packs to add missing specialist areas.
- Do not change platform-pack routing semantics.
- Do not redesign `skill-bill remove`.

## Dependency Notes

Depends on: 1
The kind surface should be narrowed before the platform-pack creation contract is simplified, so tests can reason about one remaining platform creation path.

## Validation Strategy

Add/update scaffold runtime tests for full-pack generation, payload rejection tests for `skeleton_mode` and `specialist_areas`, desktop payload tests, and CLI wizard tests. Run focused scaffold tests before broader checks.

## Next Path

Run bill-feature-task on spec_subtask_3_add-on-skeleton-wizard.md.

## Spec Path

.feature-specs/SKILL-63-scaffold-simplification/spec_subtask_2_full-platform-pack-contract.md
