# Feature: telemetry-levels
Created: 2026-04-06
Status: In Progress
Sources: conversation

## Acceptance Criteria
1. Config uses `telemetry.level: "off" | "anonymous" | "full"` instead of `telemetry.enabled: bool`
2. Backward compat: existing `enabled: true` maps to `anonymous`, `enabled: false` maps to `off`
3. Install prompt becomes a 3-option menu (off / anonymous / full) defaulting to anonymous
4. Anonymous level sends exactly what current enabled telemetry sends (no behavior change)
5. Full level adds to the telemetry payload: finding descriptions/titles, learning content (title, rule text), rejection notes, and file locations
6. `telemetry enable` / `telemetry disable` CLI commands work with the new level model
7. `telemetry status` reports the current level
8. MCP server and all runtime telemetry checks respect the new level
9. Existing tests updated, new tests cover level parsing, migration, and payload differences
10. Docs (review-telemetry.md) updated to reflect the three-level model
