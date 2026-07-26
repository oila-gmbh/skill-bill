# SKILL-50 Subtask 2 — Render runtime composition instructions

Status: Complete

Parent spec: [spec.md](./spec.md)

Depends on:
[spec_subtask_1_schema-loader-contract.md](./spec_subtask_1_schema-loader-contract.md)

## Scope

Make manifest-declared review composition visible to runtime agents through
generated `SKILL.md` wrappers. After this subtask, the hard KMP -> Kotlin
baseline rule must be generated from `platform.yaml`, not only described in
KMP `content.md`.

## Required generated section

For code-review orchestrators with baseline layers, generated `SKILL.md` should
include a section like:

```md
## Manifest-Declared Review Composition

This pack declares required baseline review layers:

1. `bill-kotlin-code-review`
   - platform: `kotlin`
   - mode: `kmp-baseline`
   - scope: `same-review-scope`
   - required: `true`

Run all required baseline layers before this pack's local review specialists.
Pass the same review scope, changed files, review IDs, applied learnings,
AGENTS.md guidance, and detected stack signals.
```

Exact wording can differ, but the generated text must be deterministic and
must carry the same machine data.

## Acceptance criteria

1. Rendered KMP `SKILL.md` includes a manifest-derived Review Composition
   section before authored platform-specific review guidance.
2. Packs with no composition do not render an empty/noisy section.
3. The generated section tells runtime agents to run required baseline layers
   before pack-local specialists.
4. The generated section names scope propagation, review IDs, applied
   learnings, AGENTS guidance, changed files, and stack signals.
5. KMP `content.md` no longer carries the hard dependency as the only source of
   truth. It may refer to "manifest-declared baseline layers" and then focus on
   KMP-specific classification, add-ons, and specialists.
6. Kotlin `content.md` keeps `kmp-baseline` behavior, but describes it as a
   manifest-declared mode rather than a caller-name exception.
7. Snapshot tests cover KMP rendered output.
8. Snapshot tests cover a pack without composition and prove the section is
   omitted.
9. `./install.sh` or render/install staging still produces no committed
   generated wrappers under source directories.

## Boundaries touched

- authoring render code in `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold`
- render snapshots under `runtime-kotlin/runtime-core/src/test/resources/snapshots`
- `platform-packs/kmp/code-review/bill-kmp-code-review/content.md`
- `platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md`
- install/render validation tests if wrapper shape changes

## Non-goals

- Do not change the schema shape from Subtask 1 unless a bug is found.
- Do not add desktop controls.
- Do not add scaffold payload fields.
- Do not encode KMP specialist trigger tables in the manifest.

## Validation strategy

Run render and snapshot tests, then:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:check)
skill-bill validate
```

Re-run `./install.sh` after source skill or renderer changes so local agent
installs pick up the new staging hash.

## Handoff prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-50-platform-pack-review-composition/spec_subtask_2_render-runtime-instructions.md`.
