# SKILL-131 Subtask 1: Audit Repair Plan Contract

## Scope

Define and persist the strict audit-repair-plan contract, stable identifiers, exhaustive criterion coverage, repair-item dependency validation, and compact durable evidence needed by later runtime phases.

## Acceptance Criteria

1. A `gaps_found` audit result contains a schema-valid repair plan covering every unmet acceptance criterion; incomplete or contradictory envelopes fail loudly through typed runtime errors.
2. Every gap and repair item has a stable unique identifier, required evidence and outcome fields, and valid acyclic dependencies; free-form prose is insufficient.
3. The runtime persists the accepted plan before taking the audit-gap edge and resumes with the identical plan and identifiers.
4. Durable state keeps a cumulative unresolved-gap ledger and compact convergence counters without storing prompts, source bodies, diffs, or raw tool output.
5. Contract schemas, Kotlin version constants, classpath bundling, typed errors, persistence mapping, and parity tests follow repository runtime-contract rules.

## Non-Goals

- Mutating repository implementation files from the audit phase.
- Executing repair items or deciding whether later audits are satisfied.
- Changing review, goal scheduling, or platform-pack behavior.

## Dependency Notes

This is the foundational subtask and has no dependencies. Subtasks 2 and 3 consume its durable contract.

## Validation Strategy

Run focused schema, persistence, mapping, and contract-version tests, including rejection cases for missing coverage, duplicate identifiers, invalid dependencies, and incompatible durable records.

## Next Path

Continue with subtask 2 to enforce exhaustive repair execution and canonical reconciliation.
