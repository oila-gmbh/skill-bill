# SKILL-50 Subtask 4 — Desktop wizard baseline-layer authoring

Status: Complete

Parent spec: [spec.md](./spec.md)

Depends on:
[spec_subtask_3_scaffold-payload-cli.md](./spec_subtask_3_scaffold-payload-cli.md)

## Scope

Add friendly desktop authoring for platform-pack baseline review layers. The UI
must let users create structured composition without learning the raw manifest
shape. It must still route all writes through the governed scaffolder payload.

## UX model

In the platform-pack creation wizard, add a section named "Baseline review
layers" or equivalent product-language label.

Controls:

- Toggle or add button: "Run another pack's review first"
- Baseline pack dropdown from discovered platform packs
- Baseline skill dropdown from the selected pack's declared code-review
  baseline and eligible review skills
- Mode dropdown with safe values for the selected skill
- Scope fixed/defaulted to `same-review-scope`
- Required checkbox defaulted to true

For KMP/Android-like routing signals, suggest:

> Use Kotlin baseline review layer

Helper text should explain:

> Run Kotlin review first for shared language risks, then add this pack's
> platform-specific reviewers.

Do not show raw YAML as the primary authoring path.

## Acceptance criteria

1. Platform-pack wizard can add, edit, and remove baseline review layers before
   dry-run.
2. Options are discovered from platform manifests rather than hardcoded to
   Kotlin, except for optional suggestion heuristics.
3. Packs without declared code-review baselines are not offered as baseline
   packs.
4. Mode options are constrained to values supported by the referenced skill.
5. Scope defaults to `same-review-scope`.
6. Required defaults to true.
7. KMP/Android-like signals produce a Kotlin baseline suggestion when the
   Kotlin pack is available.
8. Form validation blocks missing pack, missing skill, unsupported mode/scope,
   duplicate layers, and locally detectable cycles.
9. Dry-run preview includes the planned `code_review_composition` manifest edit.
10. Execute sends the same scaffold payload model introduced in Subtask 3.
11. Desktop code does not hand-write `platform.yaml`.
12. Tests cover state transitions, payload mapping, validation failures,
    suggestion behavior, and dry-run/execute parity.

## Boundaries touched

- desktop scaffold wizard state and domain models
- desktop scaffold gateway payload mapping
- desktop scaffold wizard UI
- fake scaffold gateway/testing helpers
- desktop feature tests for wizard state and UI rendering

## Non-goals

- No raw manifest editor.
- No marketplace or remote-pack discovery.
- No automatic installation of missing referenced packs.
- No support for quality-check composition.
- No support for arbitrary custom modes typed by hand in the UI.

## Validation strategy

Run desktop scaffold wizard tests, then:

```bash
(cd runtime-kotlin && ./gradlew :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest)
```

Final integration should also run the parent spec's full validation command
set.

## Handoff prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-50-platform-pack-review-composition/spec_subtask_4_desktop-wizard-authoring.md`.
