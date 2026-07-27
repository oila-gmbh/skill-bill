# SKILL-144 · Subtask 2: fallback installation, validation, and documentation

## Scope

Carry the manifest-declared generic fallback through install planning, generated
native agents, review preflight, delegated launch, validation, desktop/catalog
discovery, and user-facing documentation. Prove that installed fallback behavior is
independent of the Skill Bill source checkout and the repository under review.

## Acceptance Criteria

1. Normal review-capable install planning includes the unique manifest-declared fallback pack without hard-coding the `generic` slug.
2. Pack removal or replacement remains manifest-driven: zero fallback declarations use the documented horizontal base behavior, one declaration is selected, and multiple declarations fail loudly.
3. Native-agent rendering and durable link inventory include generic specialists for every selected provider that supports native review workers.
4. Review preflight validates only the specialists the shared routing authority will launch and does not require unrelated concrete packs for unsupported or documentation-only changes.
5. A delegated review of an unsupported stack launches generic specialists without requiring PHP, Kotlin, TypeScript, or another concrete pack.
6. A delegated review of a concrete stack launches its concrete specialists without also launching generic specialists.
7. Installation and preflight continue to work after the Skill Bill source checkout is moved or deleted and when the reviewed repository has no `skills/` or `platform-packs/` directories.
8. Validator, install, discovery, and desktop/catalog tests remain dynamic and manifest-driven.
9. README and team documentation describe fallback selection, customization, removal, and the distinction between path ownership and content tie-breaking.
10. Full repository validation passes with no generated wrappers, support pointers, or provider-specific native-agent output committed.

## Non-Goals

- Changing the semantics of a concrete pack after it has won positive path ownership.
- Installing every concrete platform pack as a workaround for ambiguous routing.
- Persisting fallback choice outside governed manifest and install contracts.

## Dependency Notes

- Depends on subtask 1.
- Reuse subtask 1's fallback field and routing result; do not create a second fallback selector in install or launch code.

## Validation Strategy

- Run install-plan and native-agent apply/preflight integration tests.
- Run delegated review launch tests for unsupported, ambiguous, concrete, and composed-stack fixtures.
- Run `skill-bill validate`.
- Run `(cd runtime-kotlin && ./gradlew check)`.
- Run `npx --yes agnix --strict .`.
- Run `scripts/validate_agent_configs`.

## Next Path

Run `skill-bill goal SKILL-144`.

