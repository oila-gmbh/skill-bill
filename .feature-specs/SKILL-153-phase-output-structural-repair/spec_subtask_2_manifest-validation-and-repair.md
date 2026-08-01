# SKILL-153 Subtask 2: Decomposition-Manifest Validation and Structural Repair

## Scope

Harden the shared feature-spec preparation path so decomposition manifests are generated, serialized, parsed, schema/coherence-validated, and read back as one governed artifact. Apply the deterministic structural-repair contract to manifest YAML when parsing fails for a purely syntactic reason.

## Acceptance Criteria

- Manifest data is constructed from the typed decomposition model and validates against the canonical decomposition-manifest schema and coherence rules before persistence.
- The serialized YAML is parsed and validated again after writing; the shared preparation path does not leave a partial bundle when manifest validation fails.
- A syntax-only YAML failure is repaired only when exactly one bounded deterministic candidate exists, then reparsed and fully schema/coherence-validated. Valid YAML is not rewritten for normalization.
- Multiple candidates, ambiguous structure, invalid field shape, missing required information, unsupported values, and coherence violations produce stable typed preparation failures rather than semantic guessing.
- Local and linear manifest rules remain intact, including source-mode handling, subtask ordering, dependency representation, runtime status, and current-subtask intent.
- Repair evidence records the original/repaired digest, format, operation, location, and contract version without exposing raw rejected YAML through normal output or telemetry.
- Tests cover valid generation, read-back verification, syntax repair, ambiguous syntax, schema/type failures, coherence failures, and atomic no-partial-write behavior.

## Non-Goals

- Changing the decomposition-manifest schema, subtask semantics, or workflow transition policy.
- Inferring missing manifest values or changing field types, names, dependencies, statuses, or intent to make a manifest pass validation.
- Integrating phase-output repair into the runtime execution loop.

## Dependency Notes

Depends on Subtask 1's versioned structural-repair result and parser rules. Reuse the canonical decomposition-manifest schema validator, coherence validator, and shared preparation writer/file-store seams.

## Validation Strategy

Run manifest schema/coherence validator tests, preparation-writer tests, serialization/read-back tests, and failure atomicity tests. Then run `scripts/validate` for the completed repository implementation.

## Next Path

Commit this subtask, then execute Subtask 3: runtime integration and conformance.

