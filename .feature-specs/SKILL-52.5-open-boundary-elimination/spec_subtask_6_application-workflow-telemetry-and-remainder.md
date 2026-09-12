# SKILL-52.5 · Subtask 6 — Application workflow, telemetry, and remainder

Parent spec: [.feature-specs/SKILL-52.5-open-boundary-elimination/spec.md](./spec.md)
Issue key: SKILL-52.5
Subtask order: 6 of 7
Depends on: subtask 5
Branch model: same-branch, commit per subtask

## Purpose

Clear the remaining allow-list entries: application workflow wire projections,
ports workflow/decomposition helpers, lifecycle telemetry payloads and service
emit methods, review/install/ide-status/review-context stragglers, and any
`other` bucket FQNs still on the list after subtasks 2–5.

## Scope

In scope:

- `skillbill.application.workflow.*`, `skillbill.application.telemetry.*`,
  `skillbill.ports.workflow.*`, `skillbill.ports.review.*`,
  `skillbill.ports.validation.*`, `skillbill.ports.goalrunner.*`,
  `skillbill.workflow.engine.*` (except items already typed in subtask 2),
  `skillbill.install.model.*`, `skillbill.review.context.*`, and remaining
  miscellaneous allow-list FQNs (~85+).
- Type lifecycle telemetry event payloads and `LifecycleTelemetryService` emit
  methods per typed contract models; adapters own `.toPayload()` for MCP/CLI
  (supersedes 2026-05-29 permanent open-boundary decision — record in
  `agent/decisions.md` in subtask 7 or here if touched).
- Type `WorkflowSnapshotView` / `WorkflowContinueView` artifact passthrough fields
  previously deferred as `@OpenBoundaryMap` debt where still on the allow-list.
- Type `PlatformManifest.customFields` only if still a public raw-map boundary;
  if schema custom fields require an extension bag, model as typed
  `CustomFieldMap` value type with contract keys, not `Map<String, Any?>`.
- Remove all remaining FQNs from allow-list except zero entries left for subtask 7
  lock verification.

Out of scope:

- Deleting the allow-list constant (subtask 7).
- Adapter dependency narrowing (SKILL-52.2 subtask 5 follow-ups already done).

## Acceptance Criteria

1. Allow-list contains at most a handful of entries (target: zero) after this
   subtask; ratchet records the new minimum.
2. Lifecycle telemetry MCP/CLI events remain wire-compatible via adapter mappers.
3. Workflow continue/update CLI/MCP payloads remain compatible or gain documented
   contract-version bumps.
4. No public raw-map declarations remain in application/domain/ports for the
   prefixes listed in Scope.
5. Architecture and adapter smoke tests pass.

## Non-Goals

- Rewriting telemetry storage schema beyond typed port models.
- New telemetry event families.

## Dependency Notes

- Subtasks 2–5 must complete so engine/domain artifacts are typed before
  application projections consume them.

## Validation Strategy

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-application:test --tests '*Workflow*' --tests '*Telemetry*')
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*Telemetry*' --tests '*Workflow*')
(cd runtime-kotlin && ./gradlew :runtime-mcp:test --tests '*Telemetry*')
```

## Next Path

`.feature-specs/SKILL-52.5-open-boundary-elimination/spec_subtask_7_allowlist-zero-lock.md`
