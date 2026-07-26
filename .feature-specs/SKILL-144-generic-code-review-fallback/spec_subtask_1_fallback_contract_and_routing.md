# SKILL-144 · Subtask 1: fallback contract, generic pack, and routing

## Scope

Introduce the schema-first manifest declaration for a code-review fallback pack,
create the governed `generic` pack source, and update the single review-routing
authority so concrete stacks require positive path ownership while the generic pack
owns zero-owner and unresolved-ambiguity cases.

## Acceptance Criteria

1. `platform-pack-schema.yaml` declares the fallback capability before Kotlin models consume it, with the shell contract version and parity tests updated when required.
2. Runtime loading exposes the fallback declaration through an anchored typed field and rejects duplicate or incoherent fallback ownership with a typed contract error.
3. `platform-packs/generic/` contains a conforming manifest, baseline code-review content, approved specialist content, and provider-neutral native-agent sources without committed generated output.
4. Concrete routing ignores content-only matches when every concrete manifest has a zero path score.
5. Content signals remain usable only to break ties among manifests sharing the same positive path score.
6. Zero concrete owners select exactly the declared generic fallback pack.
7. An unresolved tie between equally strong concrete owners selects generic, while governed composition rules such as KMP-over-Kotlin still resolve before fallback.
8. A clear concrete owner excludes generic from the routed slug set.
9. Routing tests prove that prose containing `function`, `use`, `class`, or `import` does not establish PHP or another concrete platform.

## Non-Goals

- Installer and desktop presentation changes beyond the minimum loader support needed for the contract.
- Provider-specific rendered agent files.
- Hard-coded checks for individual language slugs.

## Dependency Notes

- No subtask dependency.
- Subtask 2 depends on this subtask's schema, typed model, generic pack source, and routing outcome.

## Validation Strategy

- Run schema parity and platform-pack loader tests.
- Run `ReviewStackRouting` and declared-specialist routing tests.
- Run generic pack conformance and substance tests.
- Run `git diff --check`.

## Next Path

Proceed to subtask 2 to make the fallback installable and verify the complete review launch path.

