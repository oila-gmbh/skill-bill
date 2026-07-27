# SKILL-144: Generic code-review fallback pack

## Intended Outcome

Add a manifest-backed generic code-review pack that reviews ambiguous or unsupported
technology stacks without falsely routing them to a concrete language pack. Concrete
platform ownership must come from path evidence. Content signals may refine an
already path-matched choice but may not establish ownership by themselves.

When no concrete pack owns the changed surface, or when equally strong concrete path
evidence remains ambiguous after normal tie-breaking, review routing selects the
installed generic fallback pack. A clearly owned concrete stack never launches the
generic pack alongside it.

## Acceptance Criteria

1. The platform-pack contract can declare at most one installed code-review fallback pack through a schema-first, runtime-anchored manifest field and typed coherence validation.
2. A maintained `generic` pack provides governed baseline and specialist review content using only approved review-area names and the standard generated-output boundaries.
3. Concrete platform routing requires positive path evidence; content signals only break ties among manifests with equal positive path scores.
4. A changed surface with no concrete path owner routes to the generic fallback pack without routing PHP, Kotlin, or another language merely because prose contains tokens such as `function`, `use`, `class`, or `import`.
5. Equally strong concrete path ownership that remains ambiguous after composition rules routes to the generic fallback rather than launching every tied language pack.
6. A clearly owned concrete stack routes only to its concrete pack or governed composition and does not also route to generic.
7. Install planning, native-agent generation, review preflight, and delegated launch all use the same manifest-derived fallback decision without hard-coded platform lists.
8. Missing, duplicate, malformed, or incompatible fallback declarations fail loudly with typed errors at schema, load, install, and routing seams.
9. The generic pack is available in normal review-capable installs without requiring the repository being reviewed—or the Skill Bill source checkout—to contain `skills/` or `platform-packs/`.
10. Documentation explains concrete ownership, ambiguous ownership, unsupported stacks, fallback installation, and how custom packs can replace the shipped generic fallback.
11. Acceptance tests cover unsupported extensions, documentation-only changes, generic infrastructure files, ambiguous mixed-stack changes, concrete Kotlin/PHP/TypeScript ownership, composed KMP routing, and repositories with no local Skill Bill source tree.

## Constraints

- Governed source remains `content.md`; generated `SKILL.md`, provider-native agent output, and support pointers are not committed.
- `platform-pack-schema.yaml` changes land before Kotlin manifest model changes and retain schema-to-runtime anchored bijection.
- Discovery, installation, routing, and validation remain manifest-driven.
- The generic fallback is not a dominant stack and must not define broad path or content signals that compete in ordinary scoring.
- Existing concrete pack composition, especially KMP over Kotlin, remains unchanged when concrete ownership exists.
- Native-agent preflight trusts durable installed inventory and must not derive installation identity from the repository under review.

## Non-Goals

- Guessing a programming language from arbitrary prose.
- Replacing concrete platform specialists when positive ownership evidence exists.
- Adding a hard-coded list of supported or unsupported languages.
- Treating generated, vendored, or ignored paths as ownership evidence.
- Creating legacy `skills/<platform>/` override trees.

## Validation Strategy

- Add schema contract-version, anchored-bijection, coherence, and typed-error tests.
- Add routing unit tests for positive-path gating, content tie-breaking, ambiguity, and generic fallback selection.
- Add pack loader, substance, validator, install-plan, native-agent, and delegated-preflight integration tests.
- Run `skill-bill validate`, the focused Kotlin module checks, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Delivery Plan

1. Define the fallback contract, generic pack, and routing semantics.
2. Integrate installation, native agents, validation, documentation, and end-to-end regression coverage.

