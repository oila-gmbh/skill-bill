# SKILL-108 Subtask 1: Team Bundle Contract Foundation

## Scope

Introduce the governed team-bundle contract that all later roadmap work uses.
This subtask defines the source package shape, metadata, validation seams, and
typed runtime/domain models. It must stay inert until export/sync commands land.

Implement a strict runtime contract schema for a team bundle envelope. The
schema should cover:

- `contract_version`
- bundle identity: `bundle_id`, `version`, `channel`, `created_at`, `created_by`
- source identity: `source_repo`, `source_ref`, optional `source_commit`
- content identity: `content_hash`, `manifest_hashes`, and bundle checksum
- selected governed source roots: horizontal skills, platform packs, add-ons,
  platform overrides, native-agent source, orchestration content needed by
  install/render behavior
- compatibility: minimum runtime version or contract version where needed
- telemetry and privacy defaults, including `off`, `anonymous`, and `full`
- optional team metadata used later by roles, proposals, and hosted registry
- explicit exclusions for generated wrappers, support pointers, provider-native
  agent output, installed staging dirs, workflow DBs, and desktop state

Add the Kotlin contract-version constant, typed parse models, schema validator,
invalid-schema error, and test coverage following the existing runtime contract
patterns under `orchestration/contracts/`.

## Acceptance Criteria

1. A new team-bundle schema exists under `orchestration/contracts/` using Draft
   2020-12, strict `additionalProperties` where applicable, a pinned
   `contract_version`, and documented `x-coherence-checks` for rules JSON Schema
   cannot express.
2. A Kotlin `TEAM_BUNDLE_CONTRACT_VERSION` constant matches the schema const,
   with a parity test modeled after existing contract-version tests.
3. Runtime parsing exposes typed domain/application models for bundle metadata,
   source entries, hashes, selected channels, privacy defaults, and exclusions.
4. Invalid bundle envelopes fail through a typed
   `InvalidTeamBundleSchemaError` or equivalent `ShellContentContractException`
   subclass that includes the failing field path and reason.
5. Validation rejects any bundle that references generated `SKILL.md` wrappers,
   generated support pointers, provider-native agent output, installed staging
   artifacts, workflow DBs, or desktop app state.
6. Validation rejects missing required governed source files such as
   `content.md` and malformed platform manifests by reusing existing validators
   or loud-fail seams rather than duplicating rules.
7. The schema YAML is bundled onto the JVM classpath with the same
   configuration-cache-friendly Gradle copy pattern used by other runtime
   contracts.
8. Contract tests cover acceptance of a minimal valid bundle, rejection of
   unknown fields, missing hashes, invalid channels, generated-artifact entries,
   and stale contract versions.

## Non-goals

- No `skill-bill team export` command yet.
- No `skill-bill team sync` command yet.
- No archive writer or registry implementation.
- No desktop UI changes.
- No hosted API contracts beyond fields needed to keep the local bundle shape
  forward-compatible.

## Dependency Notes

Independent. This must land before export, sync, desktop publish, telemetry
attribution, or hosted registry work.

## Validation Strategy

Run focused runtime-contract and schema tests plus:

```bash
(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-infra-fs:test)
npx --yes agnix --strict .
```

If the schema copy task touches shared build wiring, run the full runtime check.

## Next Path

After this lands, run subtask 2 to create `skill-bill team export` and the local
bundle registry on top of the contract.
