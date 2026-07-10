# SKILL-112 Subtask 1 - Code-Review Skill Structure Standard

## Scope

Define the canonical structure every pack code-review skill follows, enforce
it with a validator-backed test, and apply the mechanical cross-pack
normalizations that are pure structure conformance. This subtask creates the
standard the kotlin/kmp elevation (subtasks 2-3) instantiates and the
remaining packs (subtasks 4-7) are modeled after.

### 1. Author the structure standard

Today the `## Focus` / `## Ignore` / `## Applicability` /
`## Project-Specific Rules` skeleton is convention only: no contract mandates
it, the kmp UI specialist legally uses a different shape, and the scaffolder
template in `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`
emits a third shape (`## Focus` + `## Review Guidance`). Author a governed
standard document under `orchestration/review-orchestrator/` (sibling to
`specialist-contract.md`, which stays the report-output contract) defining:

**Specialist skeleton** (per `bill-<platform>-code-review-<area>/content.md`)

- required H2 sections in order: `## Focus`, `## Ignore`, `## Applicability`,
  `## Project-Specific Rules`
- optional H2 `## Repo-Local Knowledge` after the rules section (ios pattern)
- H3 rule grouping inside `## Project-Specific Rules` for larger areas
- a closing severity-anchored rule in every specialist using the canonical
  wording "For Blocker or Major findings, describe the concrete
  <area-appropriate consequence> scenario"; severity vocabulary limited to
  the shared contract enum `Blocker | Major | Minor`
- lane-boundary requirements: `ui` and `ux-accessibility` specialists must
  carry explicit `## Ignore` deferrals to each other and to the `security`
  lane; no specialist may instruct running a sibling specialist
- rules must be enforceable must/must-not statements naming stack APIs and
  failure modes, not topic mentions

**Baseline skeleton** (per `bill-<platform>-code-review/content.md`)

- project/stack classification with named decision rules
- a routing table mapping diff signals to specialists
- a `## Mixed Diffs` section: keep the baseline specialists for the whole
  review; classification is a lightweight file-level pass
- per-specialist scoping that excludes generated, vendored, and
  non-stack-owned files
- a finding-discipline section (severity calibration and precondition
  verification, modeled on the ios baseline)
- merge/dedup guidance: findings stay attributed per lane, then dedup

**Manifest conventions**

- routing-signal token grammar: file-extension signals appear in both bare
  (`.kt`) and glob (`*.kt`) forms; tie-breakers include a positive dominance
  rule, negative disambiguation against adjacent packs, and a
  vendored/generated exclusion from dominance scoring
- `area_metadata.focus` strings are stack-bespoke, not copied boilerplate

**Native-agent descriptions**

- canonical pattern: "<Stack> <area> specialist code reviewer. Runs against
  <lanes>. Returns a Risk Register in the F-XXX bullet format."

**Quality-check skeleton** (per `bill-<platform>-code-check/content.md`)

- command discovery from repo build files, wrappers, and CI configuration
  before falling back to defaults
- a priority-ordered fix ladder and an explicit never-suppress rule
- targeted-vs-full-suite escalation guidance

**Authored sidecars**

- document the governed sidecar contract the kmp `compose-guidelines.md`
  already exercises: a specialist may keep one authored rubric sidecar
  co-located with its `content.md` when the rubric exceeds skeleton scale;
  reconcile `docs/skill-source-generation.md` (which cites the pattern) with
  its Source Layout allowed-files list (which currently omits it)

### 2. Align the scaffolder

Update the specialist scaffold template so `skill-bill new` emits the
canonical skeleton, and keep
`orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md` and the scaffold
exception catalog aligned per the repo contract.

### 3. Validator-backed conformance test

Add a Kotlin test (pattern: existing pack tests in
`runtime-kotlin`) that parses every
`platform-packs/*/code-review/*/content.md` and asserts the standard:
required H2 skeleton and order for specialists, required baseline sections,
canonical severity-closer wording, and no off-enum severity vocabulary
("Critical", "Nit") in severity-anchored rules. The test carries an explicit
per-pack exemption list seeded with all six packs; subtasks 2-7 each remove
their pack as it reaches conformance, and subtask 8 removes the mechanism.
The test loud-fails with a typed error message naming file and violation.

### 4. Mechanical cross-pack normalization

Landed here because they are structure fixes, not substance:

- severity closers: go and php "Critical or Major", ios "Major or Critical",
  kotlin "Major or Blocker" all become the canonical "Blocker or Major"
  wording; ios "Nit" ratings are reconciled to the enum (map to `Minor`)
- routing-signal globs: add `"*.php"` (php), `"*.kt"` (kotlin), `"*.swift"`
  (ios); go and python already carry dual forms
- do not change the shared severity enum or `specialist-contract.md`'s
  report-output sections

## Acceptance Criteria

1. A structure-standard document exists under
   `orchestration/review-orchestrator/`, covering the specialist skeleton,
   baseline skeleton, manifest conventions, native-agent description
   pattern, quality-check skeleton, and the authored-sidecar contract, each
   as testable requirements.
2. The scaffolder emits the canonical specialist skeleton and
   `SCAFFOLD_PAYLOAD.md` documents it; scaffolder acceptance tests cover the
   new shape.
3. A structure-conformance test exists, runs in
   `(cd runtime-kotlin && ./gradlew check)`, enforces the standard for
   non-exempt packs, and carries the seeded exemption list for all six
   packs with a comment tying removal to SKILL-112 subtasks.
4. `grep -rn "Critical or Major\|Major or Critical\|Major or Blocker" platform-packs/`
   returns nothing, and every severity closer in go, php, ios, and kotlin
   specialists reads "Blocker or Major".
5. No "Nit" severity rating remains in any pack content file.
6. `platform-packs/php/platform.yaml`, `platform-packs/kotlin/platform.yaml`,
   and `platform-packs/ios/platform.yaml` routing signals include the glob
   variants alongside the bare-extension forms.
7. `docs/skill-source-generation.md` Source Layout admits the documented
   authored-sidecar pattern, consistent with the standard document.
8. `skill-bill validate` passes and
   `(cd runtime-kotlin && ./gradlew check)` passes; no `contract_version`
   value changes anywhere; no generated artifacts are committed.

## Non-Goals

- No substance changes to any pack's rules (subtasks 2-7).
- No changes to the specialist report-output contract sections or the
  severity enum in `specialist-contract.md`.
- No schema changes to `platform-pack-schema.yaml` (the token grammar is a
  documentation convention in this subtask, not a schema constraint).
- No changes to `code_review_composition` or its `kmp-baseline` mode pin.

## Dependency Notes

First subtask; depends on nothing. Blocks all other subtasks: subtasks 2-7
conform their packs to this standard, and subtask 8 retires the exemption
list.

## Validation Strategy

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
grep -rn "Critical or Major\|Major or Critical\|Major or Blocker" platform-packs/ && exit 1 || true
grep -rn '"\*\.php"' platform-packs/php/platform.yaml
grep -rn '"\*\.kt"' platform-packs/kotlin/platform.yaml
grep -rn '"\*\.swift"' platform-packs/ios/platform.yaml
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

On completion, proceed to subtask 2 (kotlin pack elevation).
