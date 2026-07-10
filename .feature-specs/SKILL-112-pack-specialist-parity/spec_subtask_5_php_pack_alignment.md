# SKILL-112 Subtask 5 - PHP Pack Alignment

## Scope

Model the php pack after the kotlin/kmp reference standard. Like go, php is
substance-mature; this subtask is structural conformance plus the manifest
weaknesses the cross-pack matrix identified.

### 1. Structure conformance

- add the canonical severity closer to specialists lacking one
  (`architecture`, `testing`, mirroring the go gap); subtask 1 already
  normalized existing closer wording
- verify all ten specialists satisfy the conformance test and remove `php`
  from the exemption list

### 2. Baseline upgrades (`bill-php-code-review/content.md`)

- add finding-discipline, wave-batching, and merge/dedup sections per the
  standard (same additions as go)
- keep the existing routing table (best-in-class breadth) and Mixed Diffs
  section

### 3. Manifest enrichment (`platform-packs/php/platform.yaml`)

- tie-breakers are the weakest of any pack (a single entry): add the
  standard's three-part set — positive dominance rule, negative
  disambiguation (e.g. PHP present only as tooling/CI glue around another
  dominant stack), and a vendored/generated exclusion (`vendor/`, generated
  clients) from dominance scoring
- replace the copied-generic `area_metadata.focus` strings with PHP-bespoke
  ones in the go/python style
- the `"*.php"` glob landed in subtask 1

## Acceptance Criteria

1. All ten php specialists carry the canonical severity closer.
2. The php baseline contains finding-discipline, wave-batching, and
   merge/dedup sections consistent with the standard.
3. The php manifest carries three-part tie-breakers and PHP-bespoke
   `area_metadata.focus` strings.
4. `php` is removed from the conformance-test exemption list and the test
   passes.
5. No substance regressions in existing php rules.
6. `skill-bill validate` passes and
   `(cd runtime-kotlin && ./gradlew check)` passes including
   `PhpPlatformPackTest`.

## Non-Goals

- No rule-substance rewrites.
- No add-on system for php.
- No quality-check changes (`bill-php-code-check` is near-parity with the
  go template already).

## Dependency Notes

Depends on subtasks 1-3. Can run independently of subtasks 4, 6, and 7.

## Validation Strategy

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
```

## Next Path

On completion, proceed to subtask 6 (python pack rebuild).
