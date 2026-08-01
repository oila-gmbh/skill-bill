# SKILL-153 Subtask 1: Structural-Repair Contract and Engine

## Scope

Define the versioned phase-output validation result and repair evidence, then implement the deterministic JSON/YAML structural-repair engine in the infrastructure adapter. Integrate parser diagnostics and existing phase schemas at the adapter boundary without changing runtime transitions.

## Acceptance Criteria

- The public result is typed and distinguishes unchanged acceptance, repaired acceptance, and rejection with stable failure codes.
- Repair evidence contains the validator/contract version, format, original digest, repaired digest, operation, and source location without requiring raw payload exposure.
- Strict parsing happens before repair; a valid payload is not rewritten merely for normalization.
- Candidate generation and selection are bounded and deterministic. Exactly one candidate must remain after the repair rules are applied; otherwise the engine rejects.
- Structural repair cannot modify characters inside quoted strings or scalar values and cannot add, remove, or change field names or values.
- JSON delimiter repair covers the regression fixture and a single missing delimiter. YAML support is conservative and rejects ambiguous indentation or block-structure cases.
- Repaired output is parsed again and passed through the existing phase-specific schema validator. Parser, duplicate-key, schema, and semantic failures remain rejections.
- Adapter tests cover success, repair, ambiguity, string-content preservation, malformed/truncated input, JSON, YAML, and evidence digest correctness.

## Non-Goals

- Wiring the new result into the feature-task run loop or persistence transaction.
- Agent-based correction, semantic inference, value normalization, or a general YAML formatter.
- Changing phase schemas or the workflow's transition and recovery policies.

## Dependency Notes

Build on the existing `FeatureTaskRuntimePhaseOutputValidator` port, schema validator, Jackson/YAML support, SnakeYAML support, and JSON Schema validator. Keep all library-specific parser nodes and token details inside the infrastructure adapter.

## Validation Strategy

Run the focused runtime-domain and runtime-infrastructure tests, including the observed planner payload fixture. Verify that the repaired output can be decoded by the existing schema path and that ambiguous output produces a stable typed rejection.

## Next Path

Commit this subtask, then execute Subtask 2: runtime integration and conformance.

