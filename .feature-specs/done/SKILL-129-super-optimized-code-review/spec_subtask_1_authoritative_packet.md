# Subtask 1 - Compile one authoritative review packet

## Scope

Extend the SKILL-125 review-context schema, domain models, validators, and
preparation service so all repository, guidance, routing, composition, and lane
assignment facts are resolved once and projected deterministically. Define the
packet-consumer contract used by later routed layers and specialists.

## Acceptance Criteria

1. The schema and typed models represent immutable review IDs/revisions, changed
   hunks, matched project rules, build/test facts, learnings references, add-ons,
   direct-dependency allowlists, evidence targets, lane decisions with reasons,
   worker assignments, and expansion records.
2. One preparation service resolves these facts once, produces canonical stable
   digests and bounded projections, and rejects inconsistent ownership or drift
   through typed errors before launch.
3. A packet-consumer contract explicitly forbids downstream scope, diff, stack,
   guidance, learnings, build/test, and telemetry-ownership rediscovery.
4. Unit and schema tests cover deterministic serialization, digest stability,
   lane projection, path/hunk ownership, dependency allowlists, rule projection,
   malformed data, and cross-revision rejection.

## Non-Goals

- Do not launch agents or enforce evidence/runtime budgets in this subtask.
- Do not flatten platform composition beyond representing its lane decisions.
- Do not persist raw packet contents in telemetry.

## Dependency Notes

This is the foundation subtask and has no dependencies. Later subtasks consume
its schema-valid packet and assignments without inventing alternate models.

## Validation Strategy

Run focused runtime-domain, schema, configuration, and preparation-service tests;
run contract-version parity tests and `skill-bill validate` for the changed
contract surface.

## Next Path

Continue with subtask 2, which connects the packet to production launch and
evidence enforcement.
