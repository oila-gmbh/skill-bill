# SKILL-52.2 Subtask 3 - Review + Telemetry Typed Boundaries

Parent spec: [.feature-specs/SKILL-52.2-runtime-boundary-closure/spec.md](./spec.md)
Issue key: SKILL-52.2
Subtask order: 3 of 5
Depends on: subtask 1
Branch model: same-branch, commit per subtask

## Purpose

Move review and telemetry application/port surfaces from payload maps to typed
contracts. These are the largest remaining non-scaffold raw-map surfaces.

## Scope

In scope:

- Review:
  - Replace `ReviewService` map-returning public methods with typed application
    result models for preview import, import, feedback, triage, review-finished
    telemetry payload, and stats.
  - Replace `ReviewRepository` payload-map return methods with typed repository
    result models. Persistence repositories should return persisted facts, not
    pre-serialized CLI/MCP/telemetry payloads.
  - Keep review parsing and triage decision normalization in domain.
  - Keep SQLite details in `runtime-infra-sqlite`.
  - Move map serialization into CLI/MCP/application mapper files where output
    contracts require it.
- Telemetry:
  - Replace `TelemetryClient.fetchProxyCapabilities` and
    `TelemetryClient.fetchRemoteStats` map returns with typed result models.
  - Replace `TelemetryConfigStore.read/ensure/write` raw config maps with a
    typed config model or a deliberately named open config document type.
  - Replace `TelemetryService` public map returns with typed status, mutation,
    capabilities, remote-stats, and sync results.
  - Keep HTTP proxy DTO mapping in `runtime-infra-http` and contract DTOs in
    `runtime-contracts`.
- System:
  - If still map-returning, type `SystemService.version/doctor` or document why
    they are adapter-only serializers.
- Add architecture tests that ban new review/telemetry map-returning public
  APIs.
- Preserve CLI/MCP JSON and telemetry event behavior.

Out of scope:

- Changing telemetry event names.
- Changing SQLite schema or migration semantics unless a typed result requires a
  non-breaking query helper.
- Reworking review parser behavior.

## Acceptance Criteria

1. `ReviewService` public methods return typed models, not raw maps.
2. `ReviewRepository` does not return pre-serialized payload maps.
3. `TelemetryClient`, `TelemetryConfigStore`, and `TelemetryService` expose typed
   models or explicitly documented open config document types.
4. CLI/MCP review and telemetry output remains compatible with existing goldens.
5. Architecture tests reject future review/telemetry raw-map public APIs.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-cli:test :runtime-mcp:test --tests '*McpRuntime*' :runtime-infra-sqlite:test --tests '*Review*' :runtime-core:test --tests 'skillbill.telemetry.*' --tests 'skillbill.application.*Telemetry*')
```
