# SKILL-107 Subtask 2: `feature_addon_usage` Schema Field (Contract 1.1 → 1.2)

**Parent:** [spec.md](spec.md)
**Depends on:** subtask 1 (required — the skill-class file is renamed to `feature-task.yaml` and the family name this field keys on is settled).
**Covers:** audit finding 4 (DECIDED).

## Context

The five-plus `android-*` feature add-ons under `platform-packs/kmp/addons/` are wired only via hard-coded pointer entries in `orchestration/skill-classes/feature-task.yaml` (post subtask 1; pointers: `android-compose-implementation`, `android-navigation-implementation`, `android-interop-implementation`, `android-design-system-implementation`, `android-r8-implementation`, `android-compose-edge-to-edge`, `android-compose-adaptive-layouts`) and `ScaffoldSupport.kt`. Packs must instead declare feature-family add-on consumers in their manifests, mirroring the existing `addon_usage` mechanism for review/quality skills.

Follow the AGENTS.md 6-step contract recipe in full.

## Scope

- `orchestration/contracts/platform-pack-schema.yaml`: new top-level `feature_addon_usage` field, `x-runtime-anchored: true`, strict nested objects (`additionalProperties: false`), shaped like `addon_usage` but keyed by feature-family consumer (key on the `feature-task` family / `bill-feature-task`-matching skill names — NEVER `feature-implement`). Bump `contract_version.const` to `"1.2"`. Extend `x-coherence-checks` with feature-addon rules mirroring the existing `addon_usage` rules (pointer targets exist under `addons/`, slug uniqueness per consumer, governed add-on usage).
- `SHELL_CONTRACT_VERSION` in `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/scaffold/policy/PlatformPackManifestPolicy.kt` (and any sibling constant sites) → `"1.2"`.
- `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaContractVersionTest.kt`: pin `1.2`.
- `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/scaffold/PlatformPackSchemaAnchoredBijectionTest.kt`: cover the new anchored field (schema ↔ Kotlin parity).
- `ShellContentLoader.buildPack` / `PlatformPackSchemaValidator`: parse `feature_addon_usage` into the manifest model; malformed manifests reject through `InvalidManifestSchemaError`; loud-fail at every parse seam.
- All shipped pack manifests bump `contract_version` to `"1.2"`: `platform-packs/{go,ios,kotlin,kmp,php,python}/platform.yaml`. Only `platform-packs/kmp/platform.yaml` additionally declares `feature_addon_usage` for the `android-*` feature add-ons currently pointer-wired.
- Pointer resolution (`ScaffoldSupport.kt` `pointerFilesFor`/`resolveSkillClass` path and `orchestration/skill-classes/feature-task.yaml`): feature add-on pointers derive from routed-pack `feature_addon_usage` instead of the hard-coded skill-class pointer list; remove the seven `android-*` pointer entries from `feature-task.yaml` (the three ceremony pointers `peak-hours-warner`, `shell-ceremony`, `telemetry-contract` stay).
- `scripts/validate_agent_configs` (or the validator it drives): undeclared feature add-ons — an `addons/*.md` file consumed by a feature-family skill without a `feature_addon_usage` declaration — fail loudly.

## Acceptance criteria

1. `orchestration/contracts/platform-pack-schema.yaml` declares top-level `feature_addon_usage` with `x-runtime-anchored: true`, strict nested `additionalProperties: false`, `contract_version.const: "1.2"`, and new `x-coherence-checks` entries covering feature-addon pointer existence, per-consumer slug uniqueness, and governed usage.
2. `feature_addon_usage` consumer keys reference the `feature-task` family; the string `feature-implement` appears nowhere in the schema, manifests, or new Kotlin code.
3. `SHELL_CONTRACT_VERSION == "1.2"`, `PlatformPackSchemaContractVersionTest` pins it, and `PlatformPackSchemaAnchoredBijectionTest` proves schema↔Kotlin parity including the new field (acceptance tests).
4. Rejection tests exist for: a manifest still declaring `contract_version: "1.1"`, a malformed `feature_addon_usage` (wrong type, unknown nested key), and a `feature_addon_usage` pointer targeting a nonexistent add-on file — each failing via a typed `InvalidManifestSchemaError` (or a dedicated typed error) with a loud message.
5. All six shipped pack manifests declare `contract_version: "1.2"`; `platform-packs/kmp/platform.yaml` declares the android feature add-ons under `feature_addon_usage`; rendered install output for `bill-feature-task` with the kmp pack routed is functionally identical to the pre-change pointer set (acceptance test comparing resolved pointer filenames).
6. `orchestration/skill-classes/feature-task.yaml` no longer lists android add-on pointers; a feature add-on consumed without a manifest declaration fails `scripts/validate_agent_configs` loudly (rejection coverage in the validator script or its fixtures).
7. All four validators pass: `skill-bill validate`, `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, `(cd runtime-kotlin && ./gradlew check)`.

## Non-goals

- No new add-ons and no changes to add-on file content.
- No changes to the review/quality `addon_usage` semantics beyond shared coherence-check refactoring.
- No telemetry or workflow contract changes.
- Doc prose about the new field lands in subtask 6, not here (schema comments/`x-coherence-checks` descriptions are in scope here).

## Validation strategy

```bash
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
(cd runtime-kotlin && ./gradlew check)
./install.sh
```

Run `./install.sh` last so local staging hashes reflect renderer changes.

## Risk notes

- The `contract_version` const bump forces every manifest to move together — a partially bumped set fails loudly by design; commit atomically.
- Gradle classpath bundling of the schema YAML uses a configuration-cache-friendly `Copy` task; if the schema file set changes, keep `inputs.file` + `doFirst {}` existence guard intact.
- Watch the ordering of resolved pointers; installed shells may be hash-compared. Preserve pointer order (ceremony pointers first, then manifest-declared feature add-ons in manifest order).

## Handoff

Run bill-feature-task on `.feature-specs/SKILL-107-audit-fixes/spec_subtask_2_feature-addon-usage-schema.md`.
