---
status: Complete
issue_key: SKILL-122
subtask_id: 1
---

# SKILL-122 Subtask 1: Agent-addon contract and source validation

## Scope

Create the canonical governed source model for agent add-ons. Add
`orchestration/contracts/agent-addon-schema.yaml`, its Kotlin version constant,
parity test, classpath copy configuration, typed data model, loader, and typed
failure hierarchy. Define and validate source discovery under `agent-addons/`.

The manifest shape is:

```yaml
contract_version: "1.0"
slug: execution-budget
description: Explicit stop-boundary and compact-handoff guidance for Codex runs.
agent_ids: [codex]
consumers: [bill-feature]
```

Add/update the repository source validator and contract documentation so
`agent-addons/<slug>/agent-addon.yaml` plus `content.md` is an explicit,
validated authored-source exception. It remains outside `skills/` and
`platform-packs/` and cannot contain generated files or provider-native output.

## Acceptance Criteria

1. The agent-addon JSON Schema is authored in YAML as Draft 2020-12, with a
   pinned `contract_version: "1.0"`, strict properties, documented
   `x-coherence-checks`, and a configuration-cache-safe runtime classpath copy.
2. Kotlin declares a matching contract-version constant and a parity test; an
   `InvalidAgentAddonSchemaError` extending `ShellContentContractException`
   identifies malformed manifests at every parse seam.
3. Discovery returns typed agent-addon declarations in deterministic slug order
   and fails for an absent root only when a caller requires a declared add-on;
   an empty `agent-addons/` root is valid.
4. Validation rejects invalid/duplicate slugs, source-directory/slug mismatch,
   missing or non-regular `content.md`, wrong contract versions, unknown or
   duplicate agent ids/consumers, malformed descriptions, and duplicate source
   identities with actionable typed errors.
5. The valid-agent check uses the existing agent registry rather than a new
   hard-coded set. `bill-feature` is the only accepted consumer in this first
   contract version.
6. Existing skills, platform packs, and platform add-ons continue to parse and
   validate unchanged when no `agent-addons/` directory exists.

## Non-Goals

- Selection arguments, staging pointers, workflow persistence, or add-on
  contents.
- External agent-add-on sources or consumer expansion beyond `bill-feature`.

## Validation Strategy

Add focused schema/version, loader, and source-validator tests for valid,
missing-root, and each invalid-manifest path. Run the relevant Gradle test
modules and `skill-bill validate`.

## Dependency Notes

This is the canonical contract required by subtasks 2 and 3.

## Next Path

Proceed to `spec_subtask_2_agent-addon-delivery.md` and
`spec_subtask_3_agent-addon-feature-selection.md`.
