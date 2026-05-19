# SKILL-48 Subtask 1 — SKILL-47 cleanup

Status: Complete

Parent spec: [spec.md](./spec.md)

## Scope

This subtask owns the SKILL-47 follow-up housekeeping (the ~15 Minor/Nit findings deferred during PR #129 review) so subsequent subtasks land on a clean validator surface. It tightens the cross-boundary types on `PlatformPackSchemaValidator`, removes safe two-layer validation overlap, adds the configure-time and runtime guards around the bundled canonical schema, consolidates the duplicated `repoRootFromTest()` helper, and adds the small documentation/KDoc deferrals from SKILL-47.

Deferred to other subtasks:
- Adding any new schema under `orchestration/contracts/` (Subtask 2 — runtime contracts).
- Per-repo schema customization, `x-runtime-anchored`, or `PlatformManifest.customFields` (Subtask 3 — per-repo customization).
- Touching `AGENTS.md`. The AGENTS.md updates from D2 land with the subtasks they document (B-half with Subtask 2, A-half with Subtask 3).

## Acceptance criteria

C1. Two-layer validation overlap removed where safe: each shape-level rule (path-suffix patterns like `content.md`, pointer name `.md` rule, `..`-segment guard) lives in exactly one place — schema or Kotlin, not both. Where the Kotlin check carries human-readable message hints the schema cannot, document why the duplication remains.

C2. `PlatformPackSchemaValidator.validate(parsedYaml: Any?, slug: String)` tightens to `validate(parsedYaml: Map<String, Any?>, slug: String)` (or equivalent typed shape). `Any?` is removed from the cross-boundary contract.

C3. Unused `repoRootHint` parameter on `CanonicalPlatformPackSchemaValidator` is either dropped or wired through from `ShellContentLoader` so the on-disk fallback resolves the canonical schema deterministically when the classpath copy is unavailable (tests, IDE runs).

C4. `declared_code_review_areas` in the schema gets `uniqueItems: true`. Duplicates are rejected at the schema layer (today they slip through and rely on Map deduplication downstream).

C5. KDoc added on `PlatformManifest.routedSkillName` and `DeclaredFiles.baseline` documenting the nullable contract: "`baseline = null` ⇒ no code-review baseline; `routedSkillName` will be null/empty; downstream code-review composition is skipped." So future readers know the widening is deliberate.

C6. The Gradle Copy task in `runtime-kotlin/runtime-core/build.gradle.kts` adds `require(canonicalSchema.exists())` (or equivalent loud-fail at configure-time) so a misconfigured build cannot silently ship a JAR without the bundled schema.

C7. Classpath-resource shadow guard: after `loadSchema()`, assert the loaded schema's `$id` (or `contract_version` `const`) matches the expected value. A downstream JAR that ships a stale copy at the same resource path fails loud at startup instead of silently validating against the wrong contract.

C8. Test-helper consolidation: `repoRootFromTest()` (duplicated across `PlatformPackSchemaContractVersionTest`, `PlatformPackSchemaValidatesExistingPacksTest`, `PlatformPackSchemaViewerStateTest`, and a near-identical helper in `ShellContentLoaderParityTest`) is extracted to a single shared test helper.

## Non-goals

- **Replacing `PlatformManifest`.** Same non-goal as the parent spec.
- **Schema editing inside the UI.** Viewer stays read-only.
- **Versioning beyond a single `contract_version`.** No migrations introduced here.
- **Generating Kotlin types from the schema.**
- **Adding new top-level fields, new contracts, or new desktop tree entries** — those belong to Subtasks 2 and 3.
- **Weakening error-message quality.** Where the Kotlin layer's human-readable hint is materially better than the raw networknt output, keep it and document the duplication (per C1's "document why" clause).

## Dependencies

None. This subtask is the foundation step and must land first per the parent spec's implementation order.

## Validation strategy

`bill-quality-check` (runtime-kotlin Gradle `check`). The cleanup must keep all existing SKILL-47 tests green and add new tests for C7 (classpath-shadow loud-fail) and the consolidated helper from C8 must be used by every existing call site.

## Boundaries touched (high-level)

- `runtime-kotlin/runtime-core/scaffold` — `PlatformPackSchemaValidator`, `CanonicalPlatformPackSchemaValidator`, `ShellContentLoader`.
- `runtime-kotlin/runtime-core/build.gradle.kts` — Copy task configure-time guard.
- `runtime-kotlin/runtime-domain/scaffold/model/PlatformManifest.kt` — KDoc only.
- `orchestration/contracts/platform-pack-schema.yaml` — `uniqueItems: true` on `declared_code_review_areas`.
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/testing` — new shared `repoRootFromTest()` helper consumed by all four call sites.

## Recommended next prompt

`Run bill-feature-implement on .feature-specs/SKILL-48-runtime-contracts-expansion/spec_subtask_1_skill47-cleanup.md`
