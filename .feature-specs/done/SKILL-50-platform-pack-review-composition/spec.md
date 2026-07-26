# SKILL-50 — Platform-pack review composition contract

Status: Proposed

## Sources

- User design discussion on making KMP's dependency on the Kotlin review
  baseline structured and machine-checkable instead of prose-only.
- Current KMP/Kotlin review behavior:
  - `platform-packs/kmp/platform.yaml` routes KMP over generic Kotlin and says
    KMP layers the Kotlin baseline internally.
  - `platform-packs/kmp/code-review/bill-kmp-code-review/content.md` manually
    instructs KMP review to run `bill-kotlin-code-review` first.
  - `platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md`
    manually accepts `bill-kmp-code-review` as a caller and switches to
    `kmp-baseline`.
- Existing platform-pack contract rules in
  `orchestration/contracts/platform-pack-schema.yaml`,
  `ShellContentLoader.buildPack`, scaffold payloads, render/install staging,
  and desktop scaffold wizard flows.

## Problem

KMP review composition currently works, but the KMP -> Kotlin baseline
relationship is only a prose contract. The manifest routes to `kmp`; the KMP
skill body then tells the runtime to call `bill-kotlin-code-review`; the Kotlin
skill body reciprocally recognizes that caller as `kmp-baseline`.

That is understandable to humans, but it is not strong enough for Skill Bill's
core philosophy:

- manifests should declare machine-checkable platform contracts
- content should carry human judgment and domain heuristics
- generated wrappers should expose runtime contracts consistently
- scaffolders and desktop authoring should produce valid manifests without
  asking users to hand-write YAML
- invalid pack relationships should fail loudly before review execution

SKILL-50 makes cross-pack review composition a first-class governed contract.
The KMP pack will declare its Kotlin baseline layer in `platform.yaml`; the
runtime validates the relationship; generated `SKILL.md` renders the
composition contract mechanically; scaffold and desktop creation flows expose a
friendly "baseline review layer" UI instead of raw YAML.

## Target manifest shape

Initial shape:

```yaml
code_review_composition:
  baseline_layers:
    - platform: kotlin
      skill: bill-kotlin-code-review
      mode: kmp-baseline
      scope: same-review-scope
      required: true
```

Field meaning:

- `platform`: referenced platform-pack slug.
- `skill`: referenced code-review skill declared by that pack.
- `mode`: caller-visible mode passed to the baseline skill.
- `scope`: enum, initially only `same-review-scope`.
- `required`: whether the routed review must stop if the layer cannot run.

The field is optional. Packs with no composed baseline continue to behave as
they do today.

## Acceptance criteria

### Contract and loader

1. `orchestration/contracts/platform-pack-schema.yaml` defines
   `code_review_composition.baseline_layers` with strict JSON Schema shape,
   no unknown nested fields, and the existing contract-version parity rules.
2. `code_review_composition` is runtime-anchored because the shell consumes it
   by name.
3. `PlatformManifest` exposes typed composition data for baseline layers.
4. `ShellContentLoader.buildPack` parses and validates the new field.
5. Coherence checks reject:
   - referenced pack missing
   - referenced skill missing or not declared by the referenced pack
   - self-reference
   - composition cycles
   - duplicate baseline layers
   - unsupported `scope`
   - unsupported `mode` for the referenced skill
6. The shipped KMP manifest declares the Kotlin baseline layer.
7. Existing packs without composition still load.

### Runtime and render behavior

8. Generated `SKILL.md` for a code-review orchestrator renders a
   manifest-derived "Review Composition" section before authored `content.md`.
9. The rendered section instructs runtime agents to execute required baseline
   layers before pack-local specialists, passing review scope, changed files,
   review IDs, applied learnings, AGENTS.md guidance, and stack signals.
10. KMP `content.md` no longer carries the hard dependency as the only source
    of truth. It may refer to "manifest-declared baseline layers" but should
    keep only KMP-specific judgment.
11. Kotlin `content.md` keeps the `kmp-baseline` behavior, but the accepted
    mode should be documented as a manifest-declared mode rather than a
    caller-name special case.
12. Snapshot/render tests prove the generated wrapper includes the manifest
    composition section for KMP and omits it for packs without composition.

### Scaffolder and CLI authoring

13. Scaffold payloads can express baseline review layers for
    `kind=platform-pack`.
14. `skill-bill new --payload` writes `code_review_composition` into the
    manifest and validates it atomically.
15. Invalid payload references fail before mutation or roll back byte-for-byte.
16. Existing payloads without baseline layers remain valid.
17. `skill-bill show` / authoring inspection surfaces composition enough for
    agents and users to understand what will run.

### Desktop UI

18. Platform-pack creation wizard includes a friendly "Baseline review layers"
    section without exposing raw YAML as the primary authoring path.
19. The section lists discovered packs that declare code-review baselines.
20. The wizard can add a baseline layer with pack, skill, mode, scope, and
    required flag, using safe defaults.
21. For KMP/Android-like signals, the UI suggests "Use Kotlin baseline review
    layer" in product language.
22. Form validation blocks missing packs, missing skills, cycles when detectable
    locally, and unsupported mode/scope before Run.
23. Dry-run preview shows the manifest composition edit.
24. Execute uses the same scaffold payload path as CLI; desktop never edits the
    manifest directly.

### Documentation

25. `AGENTS.md` documents the composition contract and the rule that hard
    cross-pack delegation belongs in the manifest, not only in `content.md`.
26. User-facing docs explain baseline layers as a wizard concept and mention
    raw YAML only as an advanced/manual path.

## Non-goals

- No freeform manifest editor in the desktop UI.
- No attempt to encode every review heuristic in `platform.yaml`.
- No replacement of `content.md` as the authored domain-guidance surface.
- No composition support for quality-check in this feature. KMP quality-check
  fallback remains the explicit `kmp` -> `kotlin` rule until separately
  designed.
- No multi-version compatibility shim for old durable records or old manifests.
  Schema mismatch remains loud-fail by design.
- No marketplace or remote-pack dependency resolution.

## Design principles

- Manifest declares mandatory composition.
- Rendered `SKILL.md` turns manifest composition into runtime instructions.
- `content.md` describes platform judgment after required layers have run.
- UI/scaffolder owns friendly authoring and writes strict contracts.
- Validators enforce cross-pack integrity before runtime execution.

## Implementation order

1. **Subtask 1: schema and loader contract.**
   Add the manifest field, typed model, parser, coherence validation, and KMP
   manifest declaration.
2. **Subtask 2: render and skill prose migration.**
   Generate the Review Composition wrapper section and trim KMP/Kotlin
   `content.md` so the dependency is not prose-only.
3. **Subtask 3: scaffold payload and CLI authoring.**
   Add baseline-layer payload support and atomic manifest writes.
4. **Subtask 4: desktop wizard authoring.**
   Add friendly baseline-layer controls backed by the same scaffold payload.

Each subtask should be implemented and reviewed independently. Subtask 4
depends on Subtask 3; Subtask 2 depends on Subtask 1.

## Validation

Full final validation:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Subtasks may use narrower Gradle targets, but the final integration must run
the full project validation set above.
