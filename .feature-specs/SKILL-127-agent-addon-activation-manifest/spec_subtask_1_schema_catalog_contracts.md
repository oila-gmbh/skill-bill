---
status: Draft
issue_key: SKILL-127
subtask_id: 1
source: parent spec
---

# SKILL-127.1: Activation Schema and Catalogue Contracts

## Scope

Extend the agent add-on source contract from version `1.0` to `1.1`, adding the
required activation model and the metadata-only catalogue domain model. Migrate
existing agent add-on sources to the new schema as contextual by default, with
explicit initializer declarations for Codex agent policy and ZCode peak-hours
warning.

## Acceptance Criteria

1. `orchestration/contracts/agent-addon-schema.yaml` accepts contract version
   `1.1` with required `activation.type` and `activation.use_when` fields.
2. The schema and Kotlin constants remain in parity, and invalid manifests fail
   through typed `InvalidAgentAddonSchemaError` variants.
3. `activation.type` accepts only `initializer` and `contextual`.
4. `activation.context_tags` is accepted only for contextual add-ons, requires
   lowercase kebab-case values, and rejects duplicates.
5. The source loader exposes activation metadata, catalogue-safe descriptions,
   compatible agent ids, compatible consumers, source identity, and content
   digest without reading or emitting add-on body content into catalogue fields.
6. `agent-addons/execution-budget/agent-addon.yaml` is migrated to
   `contract_version: "1.1"` with `activation.type: contextual`.
7. A Codex agent/delegation policy initializer add-on is declared with validated
   metadata and session-compatible consumer coverage.
8. A ZCode peak-hours warning initializer add-on is declared with validated
   metadata and preserves the existing operator config contract.

## Non-Goals

- Installing startup pointers.
- Runtime prompt injection.
- Context matching beyond loading and validating manifest metadata.

## Dependency Notes

This is the first subtask and has no dependencies.

## Validation Strategy

Run targeted schema, source-loader, and scaffold validation tests, then
`skill-bill validate`.

## Next Path

Continue with subtask 2 to render and install the session-visible catalogue.
