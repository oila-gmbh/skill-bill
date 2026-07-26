# SKILL-52.1 Subtask 4 — Path Policy + Runtime-Core Shrink

Status: Complete
Parent spec: [.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md](./spec.md)
Issue key: SKILL-52.1
Subtask order: 4 of 5
Depends on: subtasks 1-3.
Branch model: same-branch (`feat/SKILL-52.1-hexagonal-runtime-hardening`); commit on completion before subtask 5.

## Why this comes after 1-3

The `runtime-core` API shrink requires CLI/MCP/Desktop and tests to already declare
direct dependencies on the modules they need, which means scaffold and install policy
must already be carved into their final ports (subtasks 2-3) so downstream modules
know what to import directly. The Path policy decision is the natural complement to
finalize domain/port contracts before final validation.

## Scope

Covers parent acceptance criteria: **AC6 (Path policy documented + enforced)**,
**AC7 (domain/port code performs no banned IO/process/HTTP/Clikt/MCP/Desktop/infra
imports — re-verify)**, **AC8 (`runtime-core` composition/runtime metadata only)**,
**AC9 (CLI/MCP/Desktop/tests declare direct dependencies)**, **AC11 (arch tests fail
loudly for `runtime-core` implementation packages / concrete infra re-exports /
adapters importing low-level implementation packages)**.

In scope:

### Path policy

Decide between:
- **Allowed value-type policy**: `java.nio.file.Path` allowed in domain/port models as
  an inert value; direct IO, `System.getProperty`, env lookup, home normalization,
  existence checks, process access remain banned outside adapter/application seams.
- **Wrapper policy**: introduce `RepoRoot`, `SkillSourcePath`, `PlatformPackPath`,
  `AgentTargetPath` wrappers with adapter conversions.

Recommended default (per spec implementation notes and boundary history of widespread
`Path` use in scaffold + install domain models): **Allowed value-type policy** unless
implementation discovers a real ambiguity wrappers would resolve. The chosen policy
must:
- be documented in `runtime-kotlin/ARCHITECTURE.md` first (source of truth);
- be enforced by architecture tests (extend
  `RuntimeArchitectureTest` / `ImplementationOwnershipArchitectureTest`);
- ban `Path`-adjacent IO calls (`System.getProperty`, env access, `Files.*` mutators,
  home normalization, existence checks) in domain/port code, including pulling
  `ReviewParsingPatterns.expandAndNormalizePath` system-property access out of
  domain-adjacent review code.

### Runtime-core API shrink

- Convert `runtime-core/build.gradle.kts` `api(runtime-application)`,
  `api(runtime-domain)`, `api(runtime-ports)` to `implementation(...)` where downstream
  modules already declare direct dependencies on those modules.
- Ensure CLI, MCP, Desktop, packaged runtime entry points, and tests compile by
  declaring direct module dependencies (do **not** rely on transitive `runtime-core`
  exposure).
- If a Kotlin-Inject generated component requires a narrow public API edge from
  `runtime-core`, keep it as narrow as possible and document the exception in
  `ARCHITECTURE.md` and in the relevant arch test.
- Keep `runtime-core` source restricted to `skillbill` and `skillbill.di` packages
  only (no implementation packages, no concrete infra re-exports). The existing
  `RuntimeGradleModuleLayeringTest` (170 LOC) and `RuntimeArchitectureDocumentationTest`
  are the natural homes — extend, do not duplicate.
- Add arch coverage that forbids adapter modules from importing low-level
  implementation packages of other modules instead of using application services and
  ports.

Out of scope:
- Telemetry / repo-validation typed-model rework beyond what is incidentally needed.
- Scaffold/install behavior changes.
- Schema codegen.
- Nested `runtime-core:data` / `runtime-core:domain` modules (explicit non-goal).
- Persisted format changes.

## Acceptance criteria

1. The chosen `Path` policy is documented in `runtime-kotlin/ARCHITECTURE.md`
   (allowed value-type policy preferred).
2. Architecture tests enforce the chosen `Path` policy and continue to ban direct
   file IO, process env access, JDBC, HTTP, Clikt, MCP adapter, Desktop UI, and
   infrastructure imports from domain/port code (AC7 re-verified).
3. `ReviewParsingPatterns.expandAndNormalizePath` (or any equivalent
   `System.getProperty`/home-normalization call in domain-adjacent code) is moved to
   an adapter or composition seam.
4. `runtime-core/build.gradle.kts` no longer uses `api(...)` for `runtime-application`,
   `runtime-domain`, or `runtime-ports` unless a Kotlin-Inject generated edge requires
   it; any remaining narrow public edge is documented in `ARCHITECTURE.md` and
   covered by an arch test.
5. `runtime-core` source remains limited to `skillbill` and `skillbill.di`; no
   implementation packages or concrete infra re-exports.
6. CLI, MCP, Desktop, packaged runtime entry points, and tests declare direct
   dependencies on the modules they use; `runtime-core` is not used as a broad API
   umbrella.
7. New arch coverage rejects:
   - `runtime-core` implementation packages or concrete infra re-exports;
   - adapters importing low-level implementation packages of other modules instead
     of using application services and ports.
8. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-goals

- Do not introduce nested `runtime-core:data` or `runtime-core:domain` modules.
- Do not change runtime composition behavior.
- Do not redo telemetry/repo-validation typed-model work unless it blocks the shrink.
- Do not redesign CLI/MCP/Desktop module structure.

## Dependencies

- Subtask 1: typed boundary foundation.
- Subtask 2: scaffold capability ports.
- Subtask 3: install capability ports.

## Reference files

- `runtime-kotlin/ARCHITECTURE.md`
- `runtime-kotlin/runtime-core/build.gradle.kts` (currently uses `api(...)` for
  runtime-application/runtime-domain/runtime-ports)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/ImplementationOwnershipArchitectureTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeGradleModuleLayeringTest.kt` (170 LOC)
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureDocumentationTest.kt`
- `runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/RuntimeDesktopBoundaryTest.kt`
- CLI/MCP/Desktop `build.gradle.kts` files to verify direct module dependencies.
- `ReviewParsingPatterns.expandAndNormalizePath` (domain-adjacent system property access).

## Validation strategy

Primary: `bill-quality-check`.
Full local pass:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew check)
```

## Recommended next prompt

Run `bill-feature-implement` on:

```text
.feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_4_path-policy-and-core-shrink.md
```

After completion, commit on `feat/SKILL-52.1-hexagonal-runtime-hardening`, then proceed
to subtask 5 (Final Validation + Contract Lock).
