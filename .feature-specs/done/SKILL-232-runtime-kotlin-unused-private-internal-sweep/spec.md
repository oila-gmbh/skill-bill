# SKILL-232: Runtime-kotlin unused private/internal sweep

## Intended Outcome

Delete unused `private` and `internal` Kotlin symbols under `runtime-kotlin`
production sources so the tree stays free of declaration-only helpers after
SKILL-132’s public/orphan sweep.

Keep the already-open ports cleanup in
`DecompositionManifestWriterSupport.kt` (package-public helpers with zero
callers). Do not alter the live application twin of that support file.

## Acceptance Criteria

1. Every removed symbol is `private` or `internal` under `runtime-kotlin`
   production sources, or is part of the already-open ports
   `DecompositionManifestWriterSupport` package-public helper deletion.
2. Each deletion is proven unused by declaration-only reference search across
   `runtime-kotlin` main and test Kotlin (and inject/KSP consumers where
   relevant) before removal.
3. The application `DecompositionManifestWriterSupport` helpers remain intact
   and compiling; the ports twin keeps only live helpers
   (`resolvedParentSpecPath`, `asStringAnyMapOrNull`).
4. Public APIs, MCP/CLI/YAML/DI wire surfaces, and schema/wire-name mirrors are
   not deleted on “no Kotlin caller” alone.
5. After the sweep, `./gradlew` compileKotlin for touched modules and `detekt`
   pass under `runtime-kotlin`.

## Constraints

- Prefer detekt `UnusedPrivate*` for private findings; use declaration-count
  scans for `internal` (detekt does not report unused internals).
- Delete in small batches; re-scan after each batch for cascades.
- Ambiguous candidates use smallest compile-delete proof; restore if anything
  fails.
- Skip `@Inject` / KSP-wired types unless no factory or constructor consumer
  exists.
- Same simple names in other packages (`asIntOrNull`, `intValue`, etc.) must
  not be deleted by bare-name counts alone.

## Non-Goals

- Public API / MCP / CLI / YAML-driven surface removals (covered by SKILL-132).
- YAGNI redesign, single-impl interface collapse, or over-engineering cuts
  beyond clear dead symbols.
- Package-public orphan sweeps beyond the already-edited ports support file.
- Test-only stubs that throw `"unused"`.
- New documentation beyond this feature-spec set.

## Validation Strategy

- `cd runtime-kotlin && ./gradlew detekt`
- CompileKotlin on touched modules (`runtime-ports`, `runtime-application`,
  `runtime-domain`, and any other module edited in a batch).
- Targeted `rg` proof per candidate before and after deletion.
- Optional focused tests when a deleted `internal` was referenced from tests.

## Delivery Plan

1. Single subtask: verify open ports cleanup, run detekt + internal scan,
   delete confirmed unused private/internal symbols in gated batches.
