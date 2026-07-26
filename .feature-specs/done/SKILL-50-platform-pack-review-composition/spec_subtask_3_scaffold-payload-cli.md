# SKILL-50 Subtask 3 — Scaffold payload and CLI authoring

Status: Complete

Parent spec: [spec.md](./spec.md)

Depends on:
[spec_subtask_1_schema-loader-contract.md](./spec_subtask_1_schema-loader-contract.md)

## Scope

Teach governed platform-pack scaffolding to create manifest-declared baseline
review layers through the typed scaffold payload. This is the shared authoring
surface that desktop will use later; desktop must not hand-edit manifests.

## Payload shape

Extend `kind=platform-pack` payloads with an optional baseline-layer list.
Suggested JSON shape:

```json
{
  "kind": "platform-pack",
  "platform": "kmp",
  "display_name": "KMP",
  "baseline_layers": [
    {
      "platform": "kotlin",
      "skill": "bill-kotlin-code-review",
      "mode": "kmp-baseline",
      "scope": "same-review-scope",
      "required": true
    }
  ]
}
```

Field names can align with existing scaffold naming conventions, but they must
round-trip deterministically into `platform.yaml`.

## Acceptance criteria

1. Scaffold payload schema/documentation describes optional baseline layers for
   `kind=platform-pack`.
2. `skill-bill new --payload` accepts valid baseline-layer payloads.
3. Scaffold writes `code_review_composition.baseline_layers` into the new pack
   manifest.
4. Existing platform-pack payloads without baseline layers remain valid and
   generate no composition section.
5. Invalid references fail before mutation or roll back byte-for-byte:
   missing referenced pack, missing referenced skill, unsupported mode,
   unsupported scope, self-reference, duplicate layer.
6. Dry-run output shows the manifest edit in a user-readable way.
7. Execute and dry-run use the same payload model.
8. `skill-bill show` or equivalent authoring inspection surfaces composition
   enough for users and agents to understand what will run.
9. Tests cover valid write, no-composition legacy payload, and invalid payload
   rollback.

## Boundaries touched

- `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`
- scaffold payload model/parsing in runtime Kotlin
- scaffold manifest rendering/editing helpers
- CLI scaffold commands and golden output, where applicable
- scaffold service parity tests
- README or getting-started docs only if they already document payload fields

## Non-goals

- Do not build desktop UI in this subtask.
- Do not infer baseline layers from freeform prose.
- Do not support baseline layers for non-platform-pack scaffold kinds yet.
- Do not implement remote dependency installation.

## Validation strategy

Run scaffold-focused tests and CLI golden tests, then:

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:check :runtime-cli:check)
skill-bill validate
```

## Handoff prompt

Run `bill-feature-implement` on
`.feature-specs/SKILL-50-platform-pack-review-composition/spec_subtask_3_scaffold-payload-cli.md`.
