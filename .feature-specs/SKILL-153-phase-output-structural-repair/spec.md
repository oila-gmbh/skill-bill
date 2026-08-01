# SKILL-153: Deterministic Phase-Output Structural Repair

## Intended Outcome

When a runtime phase emits output whose meaning is intact but whose JSON or YAML structure is malformed, the phase-output validator repairs the structure deterministically before schema validation and phase handoff. The validator must reject output when repair would require semantic inference, content changes, or an ambiguous choice.

The feature addresses failures such as an extra JSON closing bracket in planner output. It uses parser and schema-validation libraries; it never asks an agent to rewrite or explain the output.

## Scope

- `runtime-kotlin/runtime-domain` phase-output validation contracts and typed repair evidence.
- `runtime-kotlin/runtime-infra-fs` parser, structural-repair, and schema-validation implementation using the existing JSON/YAML libraries.
- Existing `FeatureTaskRuntimePhaseOutputValidator` call sites, including planning and the feature-task runtime loop.
- Decomposition-manifest generation, canonical schema/coherence validation, read-back verification, and bounded YAML structural repair in the shared feature-spec preparation path.
- Durable diagnostic evidence for the original and repaired payloads without exposing raw rejected output through normal status, telemetry, or generated artifacts.
- Conformance, integration, retry, resume, and privacy tests.

## Acceptance Criteria

- The phase-output validator exposes a versioned typed result distinguishing accepted-without-repair, accepted-after-structural-repair, and rejected output.
- Prepared decomposition manifests are generated from typed data and pass canonical schema and coherence validation before the first write and again when read back.
- JSON and YAML parsing and schema validation use the repository's existing libraries and existing phase-specific schemas; no agent call or second language-model pass is part of validation.
- A repair is allowed only when it changes syntax/structure tokens outside scalar content. The repaired payload must parse strictly, and its non-structural token content must remain unchanged.
- The validator selects a repair only when exactly one deterministic candidate is available. If zero or multiple candidates are possible, it rejects with a stable typed failure and actionable recovery guidance.
- Safe JSON delimiter cases, including the observed extra closing bracket and a single missing closing delimiter, are covered. YAML repair is limited to conservative, parser-supported flow-structure cases; indentation, quoting, anchors, duplicate keys, and ambiguous block structure are rejected.
- Schema validation and phase semantic validation run after repair. Missing fields, wrong types, invalid enum values, duplicate keys, and other semantic or content errors are never repaired or accepted as structural repairs.
- Accepted repair evidence records the format, validator/contract version, original and repaired digests, operation, and source location. Raw rejected output remains local diagnostic evidence only.
- Validation occurs at the existing phase-output validation boundary before output handoff, artifact projection, or the next phase, without adding a competing validation gate or provider-specific branch.
- Existing typed rejection, retry, recovery, quarantine, and resume behavior remains effective for truncated, oversized, malformed, ambiguous, and semantically invalid output.
- Manifest YAML is reparsed after serialization and is structurally repaired only when exactly one bounded candidate can restore valid syntax; invalid shape, schema, or coherence remains a typed preparation failure rather than a guessed repair.
- Tests cover the exact extra-bracket regression, brackets inside strings, missing delimiters, ambiguous candidates, JSON and YAML, schema/semantic failures, evidence digests, privacy boundaries, retries, and resumed runs.
- Manifest tests cover valid generation, read-back verification, syntax repair, ambiguous syntax, schema/type failures, coherence failures, and no-partial-write behavior.
- `scripts/validate` passes for the completed implementation.

## Constraints

- Keep parser and serialization types inside infrastructure adapters; domain and application contracts remain typed and dependency-direction compliant.
- Use bounded, deterministic repair attempts. Do not normalize arbitrary formatting or mutate scalar values.
- Construct and validate the complete preparation bundle before writing any artifact; a failed manifest validation must not leave a partial bundle.
- Persist accepted output, projected artifacts, repair evidence, and the transition atomically according to the existing runtime protocol; never hold a transaction open while an agent runs.
- Preserve the existing diagnostic quarantine and telemetry redaction rules.

## Non-Goals

- Asking an agent to repair, interpret, or retry malformed output.
- Inferring omitted fields, correcting values, resolving duplicate keys, or changing phase semantics.
- General-purpose YAML formatting or replacement of the configuration parser.
- Inferring missing manifest fields or repairing schema/coherence violations by changing their meaning.
- Changing workflow transitions, goal planning semantics, provider harness behavior, or adding a new CLI command.

## Dependency Notes

Subtask 1 establishes the typed result, repair evidence, deterministic repair rules, and adapter-level conformance tests. Subtask 2 applies those rules to the prepared decomposition manifest and shared preparation path. Subtask 3 depends on both and integrates the result at the existing phase-output boundary and durable runtime paths.

## Validation Strategy

Run focused Kotlin unit and adapter conformance tests first, then manifest preparation/read-back tests, integration tests for planning and runtime handoff, followed by `scripts/validate`. Inspect the resulting diff to ensure only the governed spec bundle is added during preparation.

## Next Path

Run `skill-bill goal SKILL-153` from the original Skill Bill repository after reviewing this bundle.
