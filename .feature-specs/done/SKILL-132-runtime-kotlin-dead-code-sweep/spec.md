# SKILL-132: Runtime Kotlin Dead-Code Sweep

Status: ready

## Outcome

Remove unused production code, dormant pilots, orphan contract assets, redundant MCP/CLI surfaces, and dependencies from `runtime-kotlin` while preserving every behavior that has a demonstrated runtime, generated-code, compatibility, persistence, installation, or resource consumer.

The work is decomposed because the audit spans independent compatibility boundaries and must remain resumable. Deletion is not authorized by a missing Kotlin call site alone: reflection, ServiceLoader and Gradle plugin discovery, CLI dispatch, MCP registration, serialization, database migrations, classpath resources, KSP/DI generation, Compose previews, scripts, governed skill content, and installed artifacts all count as reachability.

## Audit Baseline

The preparation sweep covered all included `runtime-kotlin` modules, approximately 87,971 lines of main Kotlin source, Gradle project dependencies, contract-resource copy tasks, production and test symbol references, CLI/MCP registration, governed skill references, and generated desktop DI output.

Confirmed high-confidence candidate groups:

- Test-owned runtime metadata shipped in production: `RuntimeSurfaceContract` and the `InstallRuntime`, `LauncherRuntime`, `NativeAgentRuntime`, `ScaffoldRuntime`, `FeatureImplementWorkflowRuntime`, and `FeatureVerifyWorkflowRuntime` marker objects are consumed by tests and documentation, not runtime behavior.
- Generic early-contract scaffolding: `ContractEnvelope`, `ContractResult`, and `ContractViolationException` have test-only consumers.
- Architecture/test catalogs and fakes in production source sets: `RuntimeModule`, `EmptyGoalPlanningPreparationRepository`, `EmptyWorkListRepository`, `EmptyReviewAttributionPort`, and `NoopGoalPlanningPreparationEnvelopeValidator` require deletion or relocation to test fixtures according to their actual role.
- Dormant review pilot code: `ReviewExecutionModePolicy`, `ReviewAssignment`, `ReviewContextPacket`, `GovernedReviewLaunch`, `ReviewEvidenceBroker`, review-evidence models, and `FileSystemReviewEvidenceBroker` lack a production construction route. `review_context_budget` is parsed from configuration but the active parallel runner uses `ReviewContextBudgetPolicy.DEFAULT`, so the configured value is not consumed.
- Suspect contract resources: review-context, execution-identity, worker-ownership, and goal-subtask-review-state schemas are copied or parity-tested, but do not all have an identified runtime validator. Each needs a producer-consumer-validation trace before deletion.
- MCP overexposure: the server advertises 53 tools and retains 10 hidden legacy aliases. Prose workflow state, feature verification, review/learnings, lifecycle telemetry, and update checking have demonstrated consumers. The `feature_task_runtime_*` family, Readian bridge, administrative/statistics/scaffold tools, continuation lookup, and compatibility aliases require individual disposition.
- Individually unreachable helpers and models include audited candidates in pointer validation, learning payloads, quality-check routing, native-agent discovery, add-on loading, stale-session wrappers/constants, lifecycle duplicate wrappers, scaffold rendering/support, review stats, first-run models, and unused typed errors.
- Desktop lexical false positives were resolved through generated-code inspection: `LocalDesktopDatabaseProvider` and `RoomRecentRepoRepository` are reached by generated KSP/DI bindings and must not be removed. Unreferenced placeholder services and design tokens remain candidates.

Static lexical results are leads, not deletion proof. Build-logic plugin classes referenced from Gradle plugin descriptors, KSP/DI-bound types, Room entities/DAOs, serialized wire names, database migrations, and test-support modules are explicitly excluded unless their non-lexical consumer is also removed.

## Acceptance Criteria

1. Every `runtime-kotlin` main source set, resource set, Gradle module edge, CLI command, MCP tool, schema-copy task, and desktop generated-code boundary receives a recorded reachability disposition: active, compatibility-retained, test-only relocation, or removable.
2. Every deleted declaration has no remaining runtime, generated-code, reflection, serialization, migration, resource, script, governed-skill, installation, or documented compatibility consumer.
3. High-confidence test-only production declarations are deleted or moved to test fixtures, and tests that only assert deleted metadata are removed rather than preserving fake product surface.
4. Dormant review-context and evidence-broker code is removed without changing active parallel review, review telemetry, triage, or learning behavior; configuration must not continue accepting a `review_context_budget` value that is silently ignored.
5. Each suspect schema family has a documented producer-consumer-validation trace. Orphan schemas, path constants, copy tasks, bundled resources, and parity-only tests are removed together; active schemas retain loud-fail validation and version parity.
6. MCP and deprecated CLI surfaces are removed only after governed skills, installed output, scripts, docs, release policy, and external compatibility have been checked. Retained compatibility aliases have an explicit rationale and removal condition.
7. The legitimate `runtime-mcp` control plane remains: agent-native workflow persistence, review/learnings, lifecycle telemetry, feature verification, and update checking continue to expose strict schemas and dispatch successfully.
8. Desktop cleanup respects generated KSP/DI consumers. Proven-unused placeholders, design tokens, empty packages, resources, libraries, and module dependencies are removed without changing desktop behavior.
9. The final sweep reports no unresolved high-confidence dead-code candidates. Any retained uncertain or compatibility-sensitive candidate has an evidence-backed rationale rather than an implicit omission.
10. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass after cleanup. Installed CLI and MCP smoke tests also pass.

## Constraints

- Preserve the user's existing dirty worktree and do not alter unrelated SKILL-128/130 work.
- Do not remove public behavior merely because repository-local call sites are absent.
- Do not delete database tables, columns, or historical migrations without a separate migration and compatibility decision.
- Do not weaken loud-fail behavior for active contract schemas.
- Do not classify Gradle plugin implementations, generated KSP/DI types, Room-generated consumers, or resource-discovered entry points as dead through lexical search alone.
- Use compile-delete or dependency-removal proofs for ambiguous candidates: make the smallest candidate deletion, compile and test its consumers, and restore/reclassify it if a non-lexical dependency appears.
- Update architecture documentation and dependency allow-lists when their described surface is removed; do not retain stale documentation as a substitute for runtime use.

## Non-Goals

- Rewriting the runtime architecture or changing feature-task behavior.
- Removing `runtime-mcp` as a module.
- Redesigning code review, telemetry, persistence, or the desktop UI.
- Replacing Kotlin Inject, KSP, Room, Compose, or the Gradle convention-plugin system.
- Performing speculative cleanup outside `runtime-kotlin` except for directly coupled schemas, governed skill references, scripts, docs, and install configuration.

## Validation Strategy

- Build an evidence ledger for every candidate with declaration path, main/test/resource/generated consumers, public compatibility status, disposition, and verification command.
- Search both symbol names and wire names across Kotlin, Gradle, resources, orchestration contracts, skills, docs, scripts, manifests, and generated registration inputs.
- Inspect generated KSP/DI sources after compilation for desktop and runtime components.
- Inspect produced JAR/distribution contents for removed schema resources and entry points.
- Run targeted module tests after each subtask, then the full repository validation gate.

## Execution Order

1. Remove proven test-owned production declarations and establish the reachability ledger.
2. Remove the dormant review pilot.
3. Prove and remove orphan runtime contract assets.
4. Rationalize MCP and CLI compatibility surfaces.
5. Verify desktop/generated boundaries and finish dependency cleanup.

