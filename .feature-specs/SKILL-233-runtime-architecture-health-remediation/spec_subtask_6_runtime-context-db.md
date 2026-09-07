# SKILL-233 · Subtask 6: Runtime-context database resolution

## Scope

Remove `dbOverride` and `dbPathOverride` plumbing from application and ports. Resolve the database path once from `RuntimeContext`, while keeping the top-level CLI `--db` behavior unchanged.

## Acceptance Criteria

1. `dbOverride` and `dbPathOverride` are absent from all `runtime-application` and `runtime-ports` main-source method signatures and request fields.
2. `DatabaseSessionFactory` methods resolve the path from bound runtime context and accept no path argument.
3. `CliRuntime` is the only CLI reader of `--db`; command classes and `CliRunInputs` no longer reference `dbPathOverride`.
4. SQLite bridges, DI bindings, services, callers, and tests carry overrides only through `RuntimeContext`, preserving default and explicit path behavior.

## Non-Goals

- Do not change the `--db` flag name or database schema.
- Do not alter identifier, status, or vocabulary behavior.
- Do not add constructor defaults that hide missing context.

## Dependency Notes

Depends on subtask 5 so contract and mapper key ownership is stable while database bridges move.

## Validation Strategy

Run the unused-parameter and signature census, CLI temp-database test, SQLite tests, and full application compilation.

## Next Path

`skill-bill goal SKILL-233`

