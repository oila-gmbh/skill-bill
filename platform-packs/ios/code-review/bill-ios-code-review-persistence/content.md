---
name: bill-ios-code-review-persistence
description: Use when reviewing iOS GRDB/SQLite persistence risks including migration safety, schema changes, and offline-sync data consistency.
---

# Persistence Review Specialist

Review only persistence issues that can corrupt data, break consistency, or create high-risk operational regressions.

## Focus

- Migration safety and forward compatibility
- Schema-change discipline for local SQLite/GRDB storage
- 1:1 coverage between SQL statements and their statement tests
- Blast radius of changes touching deep/offline sync

## Ignore

- Harmless query-style preferences
- Micro-optimizations with no correctness or sync-consistency impact

## Applicability

Use this specialist for the local persistence layer: GRDB (or equivalent SQLite wrapper) migrations, schema definitions, statement-level SQL, and code paths that participate in offline-first sync of persisted data.

## Project-Specific Rules

- A migration that has already shipped is frozen: never edit an already-applied migration's body to add or change behavior, even to fix a bug — ship a new, additive migration instead
- Migrations are additive-only; do not drop columns/tables or narrow types in a way that breaks a device that has not yet migrated forward, unless the project has an explicit deprecation/backfill path for that
- Any schema change (new table, new column, index change, constraint change) must ship with a corresponding migration in the same diff — schema drift between code and the applied migration history is a correctness risk
- Statement tests (e.g. a `{Statement}SQLStatementTests.swift`-style 1:1 test) are a recommended convention, not a universal hard rule — confirm the repo actually applies it broadly before treating its absence as a violation (in practice only a subset of statements carry one). A new or changed hand-written SQL statement without a matching test is a real but at-most-Minor coverage gap; reserve higher severity for a statement whose correctness is genuinely load-bearing (migration-affecting, sync-driving, or complex enough that a silent regression would corrupt or drop data)
- A SQL statement assembled by regex/string substitution against another statement's raw text (e.g. stripping a join by pattern-matching the `.sql` file) is fragile: its correctness is coupled to the exact formatting of the source SQL. Flag it, and note that a test which only asserts substring presence/absence does not prove the produced statement is valid runnable SQL
- Changes that touch deep/offline sync of persisted data must be evaluated for blast radius: what happens to already-synced records, in-flight sync operations, and conflict resolution when this change ships to a device mid-sync
- Reads and writes that participate in sync must preserve whatever consistency guarantee the existing sync design relies on (e.g. last-write-wins, versioned records, or conflict markers) — do not silently change write semantics for a table that sync depends on
- For Major or Critical findings, explain the data-loss, migration-failure, or sync-inconsistency consequence explicitly

## Repo-Local Knowledge

Before finalizing findings, check whether the repo under review ships its own agent-knowledge docs (e.g. `.agents/skills/*/references/*.md` and a root `AGENTS.md`/`CLAUDE.md`). When present, read them and weigh any documented hard-rule violation (e.g. an explicitly frozen-migration rule or a documented sync-consistency invariant) as a high-confidence finding. This is a read-only lookup local to the repo under review — nothing from these documents is copied into skill-bill's own tree.
