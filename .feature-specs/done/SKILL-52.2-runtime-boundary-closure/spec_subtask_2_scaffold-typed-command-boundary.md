# SKILL-52.2 Subtask 2 - Scaffold Typed Command Boundary

Parent spec: [.feature-specs/SKILL-52.2-runtime-boundary-closure/spec.md](./spec.md)
Issue key: SKILL-52.2
Subtask order: 2 of 5
Depends on: subtask 1
Branch model: same-branch, commit per subtask

## Purpose

Finish the scaffold boundary migration left intentionally open by SKILL-52.1:
`scaffold(payload: Map<String, Any?>, dryRun)` must stop being an application
and port contract.

## Scope

In scope:

- Introduce typed scaffold command request models for the `new`/`scaffold`
  payload shape. The model must represent:
  - payload contract version;
  - kind;
  - skill/platform/family/area/add-on fields;
  - body/content inputs where applicable;
  - baseline layer inputs;
  - dry-run/execute intent outside the payload where appropriate.
- Parse CLI/MCP/Desktop external maps into typed scaffold requests before
  calling application services.
- Replace `ScaffoldService.scaffold(payload, dryRun)` with a typed method.
- Replace `ScaffoldGateway.scaffold(payload, dryRun)` with a typed port method
  or split it across capability ports if a single method remains too broad.
- Keep raw payload parsing and version mismatch failures semantically identical:
  existing `ScaffoldPayloadVersionMismatchError`, `InvalidScaffoldPayloadError`,
  `UnknownSkillKindError`, `UnknownPreShellFamilyError`, and rollback behavior
  must survive.
- Preserve CLI JSON output shape and desktop scaffold wizard behavior.
- Remove the scaffold input raw-map allow-list entries after typed migration.
- Add tests proving:
  - valid legacy payload maps parse to typed requests;
  - invalid payloads fail with the same typed errors;
  - dry-run remains non-mutating;
  - execute rollback semantics remain unchanged;
  - CLI/MCP/Desktop call application through typed request models.

Out of scope:

- Redesigning scaffold payload semantics.
- Removing `PlatformManifest.customFields`.
- Rewriting the full filesystem scaffolder beyond the seam needed for typed
  request entry.

## Acceptance Criteria

1. No public application or port method accepts scaffold command input as
   `Map<String, Any?>`.
2. CLI/MCP/Desktop parse scaffold payload maps at adapter boundaries.
3. The scaffold raw-map input entries are removed from the architecture
   allow-list.
4. Existing scaffold command output remains byte-equivalent where golden tests
   exist.
5. Focused scaffold, CLI, MCP, desktop, and architecture tests pass.

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*Scaffold*')
(cd runtime-kotlin && ./gradlew :runtime-infra-fs:test --tests '*Scaffold*')
```

## Implementation Notes

- Raw-map scanner walks only `runtime-application`, `runtime-domain`, and
  `runtime-ports` source roots — confirmed in
  `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`
  `findRawMapViolations` source-root list. Therefore the parser helpers in
  `runtime-contracts/src/main/kotlin/skillbill/contracts/scaffold/wire/ScaffoldPayloadParseSupport.kt`
  are public top-level functions and do **not** require allow-list entries.
- Migration uses an overload bridge: a typed
  `scaffold(request: ScaffoldCommandRequest, dryRun)` overload is added on the
  port + application + infra-fs surfaces while the legacy raw-map overload is
  preserved. Adapters flip to the typed overload; once all callers are migrated,
  Phase 5 atomically deletes the raw-map overload and the 11 scaffold input
  raw-map allow-list rows.

