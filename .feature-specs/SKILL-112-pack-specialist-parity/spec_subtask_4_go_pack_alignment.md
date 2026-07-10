# SKILL-112 Subtask 4 - Go Pack Alignment

## Scope

Model the go pack after the elevated kotlin/kmp reference standard. The go
pack is the substance benchmark (its rules seeded the standard), so this
subtask is alignment, not a rewrite: structural conformance plus the baseline
sections the standard borrowed from other packs.

### 1. Structure conformance

- add the canonical severity closer to the two specialists lacking any
  closer (`architecture`, `testing`); subtask 1 already normalized the
  wording of the existing closers
- verify all ten specialists satisfy the conformance test and remove `go`
  from the exemption list

### 2. Baseline upgrades (`bill-go-code-review/content.md`)

Adopt the standard's baseline skeleton pieces the go baseline lacks:

- a finding-discipline section (severity calibration, precondition
  verification) modeled on the ios baseline
- deterministic-wave batching and attributed merge/dedup guidance modeled on
  the kotlin baseline
- keep the existing Mixed Diffs section, routing table (including the
  `bill-unit-test-value-check` row), and generated/vendored scoping
  exclusion — they are already the standard

### 3. Manifest polish

Confirm dual bare/glob signal forms (already present) and keep the
best-in-class tie-breakers; no area_metadata changes (already bespoke).

## Acceptance Criteria

1. All ten go specialists carry the canonical severity closer, including
   `architecture` and `testing`.
2. The go baseline contains finding-discipline, wave-batching, and
   merge/dedup sections consistent with the standard.
3. `go` is removed from the conformance-test exemption list and the test
   passes.
4. No substance regressions: existing go rules are preserved verbatim except
   where the standard requires wording changes.
5. `skill-bill validate` passes and
   `(cd runtime-kotlin && ./gradlew check)` passes including
   `GoPlatformPackTest`.

## Non-Goals

- No rule-substance rewrites; go remains the depth benchmark.
- No add-on system for go.
- No quality-check changes (`bill-go-code-check` is the standard's template).

## Dependency Notes

Depends on subtasks 1-3 (standard defined, reference pair complete). Can run
independently of subtasks 5-7.

## Validation Strategy

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
```

## Next Path

On completion, proceed to subtask 5 (php pack alignment).
