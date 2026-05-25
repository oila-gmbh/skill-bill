# SKILL-52.2 Subtask 4 - Workflow Schema Ownership + Domain Contract Tightening

Parent spec: [.feature-specs/SKILL-52.2-runtime-boundary-closure/spec.md](./spec.md)
Issue key: SKILL-52.2
Subtask order: 4 of 5
Depends on: subtask 1
Branch model: same-branch, commit per subtask

## Purpose

Tighten workflow-state ownership so domain workflow logic no longer imports
runtime contract schema validators directly while preserving loud-fail schema
validation at approved seams.

## Scope

In scope:

- Remove direct `skillbill.contracts.*` schema validator imports from
  `runtime-domain` workflow code where feasible.
- Choose the smallest behavior-preserving design:
  - application-owned workflow-state validation before/after domain transitions;
  - a domain-neutral validator interface injected by application;
  - or a typed state object that lets contract validation occur only at durable
    persistence/application seams.
- Preserve current `InvalidWorkflowStateSchemaError` behavior for malformed
  durable records, malformed step JSON, malformed artifact JSON, blank required
  fields, invalid enums, and non-exact integers.
- Keep workflow wire-map serializers only at true adapter/application contract
  seams. Public serializers that remain map-shaped must stay annotated and
  documented.
- Update `ARCHITECTURE.md` so `runtime-domain -> runtime-contracts helpers` is
  either removed or narrowed to non-validator constants if still required.
- Add architecture tests that fail if domain workflow code imports schema
  validators or contract payload mappers directly.

Out of scope:

- Changing workflow-state persisted schema.
- Changing workflow CLI/MCP output shape.
- Removing arbitrary workflow artifact maps.

## Acceptance Criteria

1. `WorkflowEngine` and domain workflow packages do not import contract schema
   validators directly.
2. Workflow-state schema validation still loud-fails at documented seams with
   existing typed errors.
3. Workflow CLI/MCP payloads remain compatible.
4. `ARCHITECTURE.md` and architecture tests encode the new ownership rule.
5. Focused workflow and architecture tests pass.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-domain:test --tests '*Workflow*')
(cd runtime-kotlin && ./gradlew :runtime-application:test --tests '*Workflow*')
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*Workflow*')
(cd runtime-kotlin && ./gradlew :runtime-mcp:test --tests '*Workflow*')
```

