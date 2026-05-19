# runtime-kotlin/ boundary decisions

This file records architectural and implementation decisions that span the
`runtime-kotlin/` boundary. Each entry is dated and explains the trade-off,
not the implementation detail.

## 2026-05-18 — Platform-pack manifest validation moves to a canonical JSON Schema

**Context.** Before SKILL-47 the rules describing
`platform-packs/<slug>/platform.yaml` lived only inside
`ShellContentLoader.buildPack` (Kotlin parser code), `ScaffoldSupport.kt`
(`SHELL_CONTRACT_VERSION`, `APPROVED_CODE_REVIEW_AREAS`, `CONTENT_BODY_FILENAME`),
and the in-memory `PlatformManifest` data class. No standalone document
described the manifest shape; new fields drifted across three files with no
mechanical link, and the desktop UI had nowhere to render a contract reference.

**Decision.** Adopt JSON Schema (Draft 2020-12) authored as YAML at
`orchestration/contracts/platform-pack-schema.yaml` as the source of truth for
the manifest shape. Validate manifests against the schema at runtime through
`com.networknt:json-schema-validator` (full Draft 2020-12 support, Apache-2.0)
bridged via Jackson `databind` (already required transitively by the validator).
The parser still produces the existing `PlatformManifest`; only the shape-rule
source moves. Cross-field coherence rules (`slug-parity`,
`areas-require-baseline`, `areas-equal-declared`,
`area-metadata-keys-subset-declared`, `pointers-unique-name-per-dir`) stay in
Kotlin because they are awkward to express in pure JSON Schema, but each is
named and documented in the schema file's `x-coherence-checks` block so the
schema document alone describes the full contract.

**Alternatives considered.**

- *Keep rules in Kotlin (status quo).* Rejected: drift across data model,
  parser, and `SHELL_CONTRACT_VERSION` is the problem this task solves.
- *Custom YAML-with-our-own-validator DSL.* Rejected: low leverage, every new
  rule needs custom validator code, no tooling ecosystem.
- *kaml + Kotlin data classes as schema.* Rejected: still couples schema to
  runtime code, no documentation surface, no UI viewer.

**Consequences.**

- Adds two runtime dependencies to `runtime-core`:
  `com.networknt:json-schema-validator` and Jackson `databind` /
  `dataformat-yaml`. Pure-JVM, no native bindings, no reflection magic.
- `SHELL_CONTRACT_VERSION` is pinned to the schema's `contract_version.const`
  via a parity test. Mismatch is a build break, not a runtime mystery.
- Desktop UI can surface the canonical schema file as a read-only viewer
  through the existing editor pane; no second copy of the schema lives in the
  UI module.
- Wrapping the validator behind `PlatformPackSchemaValidator` keeps the
  library choice local — swapping it later means rewriting one Kotlin file.
