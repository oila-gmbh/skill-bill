# SKILL-52.4 Follow-up F15 - Adapter Mapper Migration

## Scope

Move the remaining sanctioned presentation/payload mapping out of port DTOs
where practical, without changing runtime behavior or wire payloads.

## Current Bounded Debt

- `RepoValidationReport.toPayload`
- `ReleaseRefMetadata.toPayload`
- `ReviewFinishedTelemetryPayload.toPayload` and its private nested mappers

## Acceptance Criteria

1. Adapter-owned mappers emit the same payloads currently produced by the
   port-model `toPayload` functions.
2. Public port DTOs no longer expose presentation mapping where a compatible
   adapter mapper can own it.
3. Any retained `toPayload` exception remains documented in
   `runtime-kotlin/ARCHITECTURE.md` and mirrored by the architecture guard
   allow-list when it is a public raw-map boundary.
4. Existing CLI/MCP/telemetry payload golden or integration coverage remains
   byte-equivalent.

## Non-goals

- Do not change payload field names, ordering, or nullability.
- Do not broaden the open-boundary allow-list.
