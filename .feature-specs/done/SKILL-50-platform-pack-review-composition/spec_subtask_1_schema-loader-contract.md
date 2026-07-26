# SKILL-50 Subtask 1 — Schema and loader contract

Status: Complete

Parent spec: [spec.md](./spec.md)

## Scope

Add machine-checkable platform-pack review composition to the manifest contract
and runtime loader. This subtask establishes the source of truth but does not
yet change generated wrappers, scaffold payloads, or desktop UI.

## Required behavior

Introduce optional manifest field:

```yaml
code_review_composition:
  baseline_layers:
    - platform: kotlin
      skill: bill-kotlin-code-review
      mode: kmp-baseline
      scope: same-review-scope
      required: true
```

The KMP pack must declare the Kotlin baseline layer through this field.

## Acceptance criteria

1. `orchestration/contracts/platform-pack-schema.yaml` defines
   `code_review_composition.baseline_layers`.
2. Nested composition fields are strict: no unknown properties.
3. `scope` is an enum with initial value `same-review-scope`.
4. `required` is required or defaults deterministically in the parser; pick one
   and test it.
5. The top-level field is marked `x-runtime-anchored: true`.
6. Kotlin model types represent the composition contract, preferably as typed
   values under `PlatformManifest`.
7. `ShellContentLoader.buildPack` parses the field.
8. Coherence validation checks referenced packs and referenced code-review
   skills against loaded manifests.
9. Coherence validation rejects self-reference, duplicate baseline layers, and
   composition cycles.
10. Coherence validation rejects unsupported `mode` for the referenced skill.
    For this subtask, support `kmp-baseline` only for
    `bill-kotlin-code-review`.
11. Existing packs without `code_review_composition` still load.
12. KMP manifest declares the Kotlin baseline layer.
13. Tests cover valid KMP composition and every major rejection path.

## Boundaries touched

- `orchestration/contracts/platform-pack-schema.yaml`
- `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/scaffold/ShellContentLoader.kt`
- platform manifest model classes under runtime scaffold/domain model packages
- platform-pack schema tests under `runtime-kotlin/runtime-core/src/test`
- `platform-packs/kmp/platform.yaml`
- `AGENTS.md` if the runtime-contract rule needs an interim note

## Non-goals

- Do not generate runtime instructions into `SKILL.md` yet.
- Do not change scaffold payloads yet.
- Do not change desktop wizard behavior yet.
- Do not add quality-check composition.
- Do not move review heuristics from `content.md` into the manifest.

## Validation strategy

Run focused schema/loader tests first, then:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:check)
skill-bill validate
```

## Handoff prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-50-platform-pack-review-composition/spec_subtask_1_schema-loader-contract.md`.
