# SKILL-233 · Subtask 5: Wire vocabulary and contract keys

## Scope

Give every accepted or emitted token and durable payload key one owning declaration. Move parser token sets, alias maps, codec keys, mapper keys, and MCP or CLI accessors to those declarations.

## Acceptance Criteria

1. Every runtime wire token and alias is declared exactly once by an owning domain enum `wireValue` or `fromWire`; all prior accepted aliases remain covered by tests.
2. Every durable or wire payload key is declared exactly once beside its owning contract schema and version; codecs, maps, mappers, MCP, CLI, and SQLite use those constants.
3. `ProsePhaseOutputParse` owns no token set or alias map, and no main-source `setOf` or `mapOf` restates declared vocabulary.
4. `WireVocabularyArchitectureTest` indexes declarations, rejects duplicates and local restatements, includes acceptance and rejection fixtures, and reports the baseline delta.
5. Behaviour and wire output remain unchanged, with conflicting aliases resolved and documented in the report.

## Non-Goals

- Do not generate constants from YAML schemas.
- Do not remove log, help, filename, or prompt prose literals.
- Do not change identifier types or database path plumbing.

## Dependency Notes

Depends on subtask 4 so token ownership follows the final closed status types.

## Validation Strategy

Run the vocabulary scanner fixtures, table-driven alias tests, contract parity tests, and affected codec/parser tests.

## Next Path

`skill-bill goal SKILL-233`

