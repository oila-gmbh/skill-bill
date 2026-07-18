# Subtask 1: Remove Proven Test-Owned Production Declarations

## Scope

Establish the deletion-evidence ledger and remove declarations whose only consumers are tests or documentation. This includes the runtime-surface metadata hierarchy and marker objects, early generic contract wrappers, test catalogs/fakes incorrectly shipped in main source sets, and individually confirmed unused helpers, constants, errors, and models.

Initial targets include:

- `RuntimeSurfaceContract`, `RuntimeSurfaceStatus`, `InstallRuntime`, `LauncherRuntime`, `NativeAgentRuntime`, `ScaffoldRuntime`, `FeatureImplementWorkflowRuntime`, and `FeatureVerifyWorkflowRuntime`.
- `ContractEnvelope`, `ContractResult`, and `ContractViolationException`.
- `RuntimeModule`, with architecture tests changed to derive module/package ownership from authoritative Gradle/source inputs or moved test-fixture data.
- Test-only empty/no-op ports relocated out of production artifacts where practical.
- Confirmed unused helpers in scaffold pointer/render/support code, native-agent discovery, learning payloads, quality-check routing, stale reconciliation, lifecycle duplicate detection, and unused result/error models.

## Acceptance Criteria

1. Every removed declaration has an evidence-ledger entry showing no non-test, resource, generated, serialized, reflective, CLI, or MCP consumer.
2. The runtime-surface metadata system and marker objects are absent from production sources, tests, documentation, and module dependencies unless a real runtime consumer is discovered and recorded.
3. Generic contract wrappers with test-only use are deleted together with tests that validate only those wrappers.
4. Test fakes and no-op implementations needed by tests live in test/test-fixture source sets rather than production artifacts; unused ones are deleted.
5. Individually removed helpers leave no stale imports, comments, documentation claims, constants, or architecture allow-list entries.
6. Targeted compilation and tests for runtime-contracts, runtime-core, runtime-domain, runtime-application, runtime-ports, runtime-infra-fs, runtime-infra-sqlite, runtime-cli, and runtime-mcp pass.

## Non-Goals

- Removing CLI or MCP compatibility names.
- Changing database schemas or migrations.
- Removing Gradle plugin implementations or KSP/DI declarations.
- Removing utilities merely because they are mostly test-covered when a production caller exists.

## Dependency Notes

This is the first subtask. Its evidence ledger and deletion rules govern every later subtask.

## Validation Strategy

- Repository-wide symbol and wire-name searches including tests, resources, Gradle, skills, docs, and scripts.
- Compile-delete proof for ambiguous declarations.
- Targeted module tests plus Detekt.
- Confirm architecture documentation describes behavior rather than deleted metadata catalogs.

## Next Path

Proceed to Subtask 2 after the reachability ledger and proof rules are in place.

