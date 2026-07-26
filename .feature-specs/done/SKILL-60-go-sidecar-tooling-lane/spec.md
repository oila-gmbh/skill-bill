# SKILL-60 - Go sidecar tooling lane for KMP-first desktop and runtime distribution

Created: 2026-05-31
Status: Draft
Issue key: SKILL-60
Parent: follow-up to SKILL-55 launch readiness and prior Go platform exploration

## Sources

- User discussion on 2026-05-31:
  - keep product UI in KMP/Kotlin;
  - evaluate Go for reduced runtime prerequisites and easier cross-platform binary release;
  - use available macOS Actions runner for release-grade macOS artifacts.
- Existing repository constraints from `AGENTS.md`:
  - KMP and Kotlin are protected platform surfaces;
  - platform behavior is manifest-driven;
  - contract/schema changes must loud-fail and remain versioned;
  - generated artifacts stay out of source.
- Existing launch/runtime direction in SKILL-55 and runtime-kotlin release surfaces.

## Problem

`skill-bill` currently centers runtime and desktop behavior in Kotlin/KMP, which is
correct for product coherence, but operational tooling still inherits JVM-era
constraints for short-lived utility paths. Some utility workloads (validation,
manifest inspection, artifact integrity checks, release preflight) would benefit
from a tiny static binary lane with low cold-start and minimal host
requirements.

The risk is accidental architecture drift: introducing Go must not split
business contracts, duplicate orchestration rules, or weaken governed
manifest/routing behavior.

## Goals

1. Keep KMP/Kotlin as the product and UI source of truth.
2. Introduce a bounded Go sidecar tooling lane for operational helper tasks
   only.
3. Improve operator ergonomics for short-lived commands by providing optional
   single-binary tooling that does not require a local JVM.
4. Add a straightforward multi-OS build/release pipeline:
   - Linux runner builds Linux/Windows artifacts;
   - macOS runner builds/signs/notarizes macOS artifacts.
5. Preserve existing runtime contracts and loud-fail behaviors; Go helpers must
   consume published contracts, not redefine them.
6. Keep the feature reversible: if Go helpers underperform, removal should not
   affect core runtime or desktop app behavior.

## Non-Goals

- No rewrite of runtime-kotlin orchestration or workflow engine in Go.
- No replacement of KMP UI or Kotlin shared/domain modules.
- No forked manifest schema with separate Go-only semantics.
- No migration of core `bill-feature-implement`, `bill-feature-verify`, or
  workflow persistence logic to Go.
- No mandatory dependency on Go for contributors who only use Kotlin paths.

## Scope

In scope:

- define one initial Go helper boundary (pilot) with narrow responsibilities;
- wire Kotlin runtime/CLI to optionally invoke the helper when present;
- add fallback behavior to Kotlin-native implementation when helper is missing;
- define release/build topology for helper binaries across linux/macos/windows;
- enforce contract parity tests between helper output and Kotlin baseline.

Out of scope:

- broad helper suite at once;
- speculative platform-pack or skill taxonomy expansion unrelated to helper
  operations;
- UI framework or desktop surface changes.

## Target User Experience

1. Default users continue using existing `skill-bill` commands with no workflow
   changes.
2. Operators can enable an optional helper mode (explicit flag/env/config).
3. When enabled and binary is available, helper-backed command paths run with
   fast startup and explicit provenance output (helper version/build id).
4. When helper is unavailable or fails, command falls back to Kotlin path with
   typed diagnostics, not silent behavior drift.

## Pilot Candidate

Pilot the Go helper on one low-risk, high-frequency utility path:

- `feature-spec` artifact integrity preflight (file shape, naming, schema
  presence checks), or
- install staging integrity verification (hash/file presence checks).

Selection criteria:

- deterministic input/output;
- no durable state mutation;
- bounded filesystem-only behavior;
- easy parity assertion against existing Kotlin implementation.

## Architecture Constraints

1. Kotlin remains the only owner of orchestration decisions and durable workflow
   mutation.
2. Go helper uses typed request/response contracts defined under
   `orchestration/contracts/` (new schema only if strictly required).
3. Any new runtime contract follows repo contract policy:
   - Draft 2020-12 schema in YAML;
   - `<CONTRACT>_CONTRACT_VERSION` constant;
   - schema-version parity test;
   - typed `Invalid*SchemaError` loud-fail path;
   - packaged on JVM classpath as required.
4. Helper invocation must be adapter-scoped (no domain-layer process spawning).
5. Generated helper outputs/install artifacts are not committed.

## Rollout Plan

### Phase 1: Contract and Kotlin adapter seam

- define helper command contract (or reuse existing one);
- add Kotlin adapter interface + default implementation;
- add feature flag/config switch for helper enablement;
- add strict fallback and diagnostics behavior.

### Phase 2: Go pilot implementation

- create `tooling-go/` (or equivalent) module with one helper command;
- implement contract decode/encode + deterministic behavior;
- emit machine-parseable errors with stable codes.

### Phase 3: Build and release pipeline

- linux runner builds linux + windows helper artifacts;
- macOS runner builds macOS artifacts;
- macOS signing and notarization step for release artifacts;
- attach helper artifacts to release and expose checksums.

### Phase 4: Validation and operational hardening

- parity tests (Go output vs Kotlin baseline fixtures);
- failure-mode tests (missing binary, version mismatch, bad payload);
- docs updates for optional enablement and support boundaries.

## Acceptance Criteria

1. A new SKILL-60 feature directory exists at
   `.feature-specs/SKILL-60-go-sidecar-tooling-lane/` with this governing spec.
2. A single pilot helper use-case is selected and documented with explicit
   ownership boundary.
3. Kotlin runtime includes a helper adapter seam with explicit mode selection:
   - `kotlin_only` (default);
   - `helper_preferred` (fallback to Kotlin);
   - optional `helper_required` (typed loud-fail).
4. Helper invocation provenance is visible in command output/logging:
   helper name, version, and resolved binary path.
5. If helper binary is missing, incompatible, or exits non-zero, Kotlin fallback
   occurs in `helper_preferred` mode with a typed diagnostic event.
6. If `helper_required` is set and helper cannot execute successfully, command
   fails loudly with typed error and remediation hint.
7. No domain-layer code directly shells out; invocation is isolated in runtime
   adapter/composition layers.
8. Any new helper request/response schema is versioned under
   `orchestration/contracts/` and covered by contract-version parity tests.
9. Go helper implementation is deterministic and side-effect-bounded to the
   declared pilot scope.
10. CI publishes helper artifacts for:
    - linux amd64/arm64 (if supported by current release matrix),
    - windows amd64,
    - macOS arm64 (and amd64 if supported by current release matrix).
11. macOS release artifacts are signed/notarized via macOS runner before
    publication.
12. Release notes and docs clearly state:
    - helper lane is optional;
    - KMP/Kotlin remain canonical product/runtime surfaces;
    - fallback behavior and support boundaries.
13. Validation coverage proves parity between Kotlin and helper outputs for the
    pilot command via shared fixtures.
14. Maintainer validation passes:
    - `skill-bill validate`
    - `(cd runtime-kotlin && ./gradlew check)`
    - `npx --yes agnix --strict .`
    - `scripts/validate_agent_configs`

## Validation Strategy

- Unit tests:
  - Kotlin adapter mode selection and fallback matrix.
  - Typed error mapping for helper failures.
- Contract tests:
  - schema contract version parity;
  - helper payload validation/rejection.
- Golden/fixture parity tests:
  - same inputs through Kotlin baseline and Go helper produce identical
    semantic results.
- Integration tests:
  - CI smoke for each target artifact;
  - macOS notarization success path;
  - runtime behavior with helper absent/present/mismatched version.

## Risks and Mitigations

1. Risk: Dual-language maintenance overhead.
   Mitigation: one helper pilot only, strict scope gate, ADR-style checkpoint
   before expansion.
2. Risk: Contract drift between Kotlin and Go.
   Mitigation: shared fixture parity tests and versioned schema enforcement.
3. Risk: Operational confusion about canonical runtime.
   Mitigation: explicit docs and startup provenance output.
4. Risk: macOS release friction.
   Mitigation: keep signing/notarization isolated to macOS runner and fail
   release loudly on notarization errors.

## Open Questions

- Which pilot path yields the highest ROI with lowest coupling:
  `feature-spec` preflight or install integrity verification?
- Should helper artifacts be bundled into desktop installers or downloaded on
  demand at runtime?
- Is `helper_required` mode needed initially, or should we launch with only
  `kotlin_only` and `helper_preferred`?
- Should helper mode selection live in CLI flags, config file, env vars, or all
  three with precedence rules?

## Exit Criteria for Expansion Beyond Pilot

Expand helper scope only if all are true after at least one release cycle:

1. measurable cold-start improvement on pilot path;
2. no unresolved parity defects across supported OS targets;
3. no increase in operator support burden from mode confusion;
4. clear next candidate path with same low-coupling profile.
