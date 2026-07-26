# SKILL-47 — Platform pack schema: single source of truth + UI viewer

Status: In Progress

## Sources

- Conversation thread between Braian and Claude on 2026-05-18 covering `bill-code-review` deletion, schema rigidity around `declared_files.baseline`, and the broader question "where is the platform pack shape defined?".
- The just-landed schema relaxation in this branch (`baseline`, `declared_files`, `area_metadata` all optional; coherence rule: areas without baseline → error). That work proved the parser is the de-facto schema and exposed the cost of splitting schema knowledge across three files.

## Problem

Today the rules that define what a `platform-packs/<slug>/platform.yaml` file is allowed to contain are split across three places, none of which is a single canonical document:

1. **Data model** — `runtime-kotlin/runtime-domain/.../scaffold/model/ScaffoldModels.kt`. The Kotlin data classes (`PlatformManifest`, `DeclaredFiles`, `RoutingSignals`, `PointerSpec`) are the in-memory shape every consumer agrees on.
2. **Parser/validator** — `runtime-kotlin/runtime-core/.../scaffold/ShellContentLoader.kt`. `buildPack`, `parseDeclaredFiles`, `parseRoutingSignals`, `parseAreaMetadata`, and `parsePointers` are the authoritative rules: required fields, value constraints, coherence checks.
3. **Contract version pin** — `runtime-kotlin/runtime-core/.../scaffold/ScaffoldSupport.kt:10` defines `SHELL_CONTRACT_VERSION = "1.1"`.

Concrete consequences observed in practice:

- A user who wants to understand what `platform.yaml` can contain has to read `buildPack` top to bottom — there is no docs page, no JSON Schema, no schema viewer.
- When a field's optionality changes (as it just did for `baseline`), the change has to land in the data model, the parser, every downstream null-guard call site, and the tests — with no mechanical link between them.
- The desktop UI can render `platform.yaml` files in the tree but cannot show users what shape that YAML is allowed to take.
- Authoring drift is silent: adding a new field is a code change in 2–3 files and no documentation surface notices.

The user's stated mental model of a platform pack: a user-defined skill container whose value is platform-aware override — not a predefined "must have code-review baseline" structure. The schema should reflect that mental model in one document, not be re-derived by reading parsers.

## Acceptance criteria

1. There is a single, canonical file in the repo (proposed: `orchestration/contracts/platform-pack-schema.yaml` or `.json`) that fully describes the structure of `platform-packs/<slug>/platform.yaml`, including every field, its type, whether it is required, its value constraints, and the coherence rules (e.g. "areas non-empty requires baseline").
2. The schema file declares the same `contract_version` value that `SHELL_CONTRACT_VERSION` references today, and the two stay in sync (compile- or test-time check, not human discipline).
3. `ShellContentLoader.buildPack` validates incoming `platform.yaml` against the schema file (loaded once at runtime) instead of hand-coding the rules. The parser still produces the existing `PlatformManifest` data model; only the validation source moves.
4. Hand-coded coherence rules that don't fit JSON-Schema-class validators (e.g. "areas implies baseline", "every declared area must appear in `declared_files.areas`") remain in Kotlin but are documented in the schema file as named coherence checks the parser enforces, so the schema file alone describes the contract.
5. Existing repo `platform.yaml` files (`platform-packs/kotlin/platform.yaml`, `platform-packs/kmp/platform.yaml`) continue to load identically — no migration of authored files. The relaxations landed in this branch (`baseline` optional, `declared_files` optional, `area_metadata` optional, coherence check on areas-without-baseline) are preserved in the schema file.
6. The desktop UI gains a **read-only schema viewer** entry point reachable from the Skill Bill tree (proposal: a top-level "Platform pack schema" row under a "Contracts" group, or a button on a `PLATFORM_PACK` node). Clicking it opens an editor pane that renders the schema file with syntax highlighting. No editing in this scope.
7. The schema viewer reads from the same canonical file the runtime parser uses; there is no second copy of the schema embedded in the UI.
8. Tests:
   - A unit test that loads the canonical schema, builds an instance of `platform-packs/kotlin/platform.yaml` and `platform-packs/kmp/platform.yaml`, and asserts both validate successfully.
   - A unit test for each documented violation: missing `platform`, missing `routing_signals.strong`, `declared_files.areas` set with no `baseline`, `area_metadata` entry for an undeclared area, etc. Each test asserts the schema validator surfaces the expected error and the message names the field.
   - A test that pins `contract_version` parity between the schema file and `SHELL_CONTRACT_VERSION`.
   - A desktop UI test that opens the schema viewer entry point and confirms the rendered content matches the canonical file byte-for-byte.

## Non-goals

- **Per-repo schema customization.** This task does not let users edit the schema in their repo to add or remove fields and have the runtime adapt. The runtime still has named dependencies on specific fields (`baseline` drives `routedSkillName`, `declaredQualityCheckFile` drives the quality-check pipeline). A future task can take that step; this one does not.
- **Schema editing inside the UI.** The viewer is read-only. Editing the schema file means editing it like any other repo file (via Git, or eventually a separate editing affordance that produces a commit/PR). The motivation is to keep the runtime contract and the schema in lockstep without introducing a runtime/schema desync window.
- **Replacing the data model.** `PlatformManifest`, `DeclaredFiles`, etc. remain as today. The schema describes the YAML shape; the data model continues to be the in-memory shape consumers depend on.
- **Migrating `bill-skill-remove`, scaffolding flows, or render flows to read the schema directly.** Those continue to interact with the Kotlin data model. Only `ShellContentLoader` reads the schema at runtime.
- **Versioning beyond a single `contract_version` string.** No schema migrations, no multi-version validators. If the schema's contract version changes, every `platform.yaml` must declare the new version; mismatch is still a hard error.

## Open questions

- **Schema format choice.** JSON Schema (Draft 2020-12) has mature Kotlin tooling and is the most standard. YAML Schema is friendlier to author by hand. A bespoke YAML-with-our-own-validator file is the lightest-weight but lowest-leverage. Recommend JSON Schema with the file written in YAML for authoring comfort.
- **Schema location.** `orchestration/contracts/` would group it with the existing telemetry/workflow contracts. `docs/contracts/` would surface it as docs. The choice affects whether the UI reads from `orchestration/` (runtime) or from `docs/` (documentation). Recommend `orchestration/contracts/platform-pack-schema.yaml` to keep parser and viewer reading the same file.
- **Coherence rules.** JSON Schema can express most of the current rules, but "every declared area must appear in `declared_files.areas`" requires cross-field validation that's awkward in pure JSON Schema. Options: (a) keep these as named Kotlin coherence checks documented in the schema file's prose section; (b) express them via `dependentRequired`/`if/then` keywords if supported by the chosen validator; (c) move them into a side-car DSL. Recommend (a) for this task to avoid coupling to a specific JSON Schema feature set.
- **Schema viewer UX.** Open in the existing editor pane vs. a dedicated docs pane. Whether the viewer should fold/unfold sections, validate against the open `platform.yaml`, or simply render the raw file. Recommend simplest: render raw file in the existing editor pane, read-only.

## Risks

- **Validator-library choice locks us in.** Picking a JSON Schema validator that lags behind the spec or doesn't support our coherence keywords becomes painful to swap later. Mitigation: wrap the validator behind a thin Kotlin interface in `runtime-core` so swapping is local.
- **Two sources of truth during transition.** Between the time the schema file lands and the time the parser is wired to read from it, drift is possible. Mitigation: land both changes in the same PR; gate the merge on the parity test.
- **Surface visibility.** Adding a schema entry to the desktop tree consumes UI real estate. Mitigation: tuck it under a `Contracts` group only when at least one contract file exists; do not surface it on first-run for a brand-new repo.

## Implementation order

1. Write the canonical schema file capturing the current contract verbatim (including the relaxations from this branch).
2. Add the parity test (schema file's `contract_version` == `SHELL_CONTRACT_VERSION`).
3. Add the "loads existing kotlin and kmp packs" test using the schema validator directly (proves the schema file is correct before touching the parser).
4. Refactor `ShellContentLoader.buildPack` to validate via the schema; keep named coherence checks in Kotlin.
5. Add the schema viewer entry point in the desktop tree.
6. Add the desktop UI test.
7. Document the schema file's path in `AGENTS.md` so future agents land changes in the canonical place.
