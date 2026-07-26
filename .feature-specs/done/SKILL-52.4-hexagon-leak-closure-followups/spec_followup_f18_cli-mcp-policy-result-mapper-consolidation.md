# SKILL-52.4 Follow-up F18 - CLI/MCP Policy and Result Mapper Consolidation

## Scope

Consolidate duplicated CLI/MCP policy and result-mapper behavior behind a shared
guard surface while preserving adapter-specific rendering and transport details.

## Current Bounded Debt

- `refuseInstallMutationDuringGoalContinuation` is shared policy but still
  called from multiple CLI install paths beyond the stale line list captured in
  earlier notes.
- CLI and MCP result mappers encode overlapping status/result-to-payload and
  status/result-to-exit/error policy in separate adapter surfaces.

## Acceptance Criteria

1. Install-mutation refusal during goal continuation is enforced through one
   shared application or adapter-policy surface.
2. CLI and MCP result mappers share policy for equivalent outcomes while keeping
   transport-specific payload/rendering code at the adapter edge.
3. Coverage proves the shared guard applies to every current CLI install
   mutation path and the matching MCP surface, if present.
4. Existing CLI exit-code behavior and MCP result payload contracts remain
   compatible unless a future spec explicitly changes them.

## Non-goals

- Do not change install planning or apply behavior.
- Do not collapse CLI terminal rendering and MCP payload shaping into one
  adapter implementation.
