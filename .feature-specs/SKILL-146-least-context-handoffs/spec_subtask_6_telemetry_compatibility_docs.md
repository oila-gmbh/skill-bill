# SKILL-146 Subtask 6: Privacy telemetry, compatibility, documentation, and contract integration

## Scope

Integrate privacy-safe context measurements and failures across runtime, prose, verification, and delegated review. Persist byte/token estimates and counters without prompt, diff, source, receipt, or raw tool contents. Complete compatibility messages, architecture/runtime/prose documentation, governed `content.md`, CLI/MCP continuation documentation, schema references, persistence mappings, fixtures, and goldens. Keep telemetry/progress stores separate from domain artifacts.

## Acceptance Criteria

1. Parent AC 3 and 4 document and expose durable private/delivered separation with actionable typed failures.
2. Parent AC 20 keeps telemetry and progress diagnostics out of domain artifacts.
3. Parent AC 23 provides explicit legacy incompatibility and restart/out-of-band migration guidance.
4. Parent AC 24 records required privacy-safe measurements and counters without payload content.
5. Parent AC 25 proves telemetry/privacy absence as well as required measurement presence.
6. Parent AC 26 aligns documentation, architecture, governed sources, schemas, constants, mappings, continuation surfaces, fixtures, and goldens without committing generated artifacts.
7. Parent AC 30 documents and tests the single authoritative delegated-review rules source.

## Non-Goals

- Recording prompt/payload content, silently migrating legacy records, or editing installed/generated artifacts.
- Running installer/uninstaller flows or changing unrelated policy.

## Dependency Notes

Depends on Subtasks 3, 4, and 5 so documentation describes implemented contracts.

## Validation Strategy

- Telemetry privacy serialization/persistence and domain-artifact absence tests.
- Counter, byte/token estimate, stale checkpoint, and projection-failure tests.
- Compatibility-message and documentation/source consistency tests.
- Focused contract validation and governed render previews without committing output.

## Next Path

Proceed to Subtask 7 after this integration subtask is committed.

