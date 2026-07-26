# SKILL-112 - Pack Review Specialist Parity

Status: decomposed; execution not started.

## Outcome

The kotlin/kmp pack pair becomes the state-of-the-art reference for platform
packs — canonical specialist structure, stack-named failure modes, correct
lane boundaries, full coverage across the pair, discovery-based
quality-check — and every other shipped pack (go, php, python, ios) is
remodeled after that standard. The standard itself is codified as a governed
document and enforced by a validator-backed conformance test, so future
packs cannot drift.

This decomposed spec supersedes the earlier single-spec version of
SKILL-112: the python substance upgrade and cross-pack severity/glob fixes
it contained are carried forward in subtasks 1 and 6.

## Findings Basis

Three audits (2026-07-09) ground the scope: the python-pack audit (thin
two-section specialists), the kotlin/kmp audit (correct skeleton but almost
no Kotlin-named failure modes; kmp coverage crack for non-UI multiplatform
concerns, broken lane boundaries, Android-only content under a KMP label),
the ios audit (mature skeleton overfit to one app, stub quality-check,
pre-Swift-Concurrency, off-enum severities), and a cross-pack capability
matrix (structure skeleton is convention not contract; three severity
phrasings plus "Nit"; inconsistent signal grammar; quality-check depth from
86 lines to a 24-line stub).

## Decomposition

1. **Code-review skill structure standard** — author the governed standard
   (specialist skeleton, baseline skeleton, manifest conventions,
   native-agent description pattern, quality-check skeleton, authored
   sidecar contract), align the scaffolder, add the conformance test with a
   seeded exemption list, and land the mechanical cross-pack normalizations
   (severity wording, routing globs).
2. **Kotlin pack elevation** — Kotlin-named failure modes across all eight
   specialists, baseline Mixed Diffs / vendored exclusion / finding
   discipline, quality-check command discovery, manifest enrichment.
3. **KMP pack elevation** — new platform-correctness specialist closing the
   multiplatform coverage crack, lane-boundary fixes, canonical UI
   specialist shape with the compose-guidelines sidecar as single source,
   Compose Multiplatform identity, semantics-API depth in ux-accessibility,
   add-on reachability repairs.
4. **Go pack alignment** — closers on architecture/testing, baseline
   finding-discipline/waves/merge sections; go remains the substance
   benchmark.
5. **PHP pack alignment** — same as go plus three-part tie-breakers and
   bespoke area metadata.
6. **Python pack rebuild** — full skeleton adoption carrying every
   python-audit recommendation, baseline mixed-diffs restoration.
7. **iOS pack alignment** — quality-check rebuild, de-overfitting with
   generic-stack applicability branches, Swift Concurrency and
   background-execution failure modes, manifest and agents.yaml conformance.
8. **Conformance gate retirement** — remove the exemption mechanism, full
   validation matrix, install refresh, catalog touch-up.

Subtask 1 blocks everything; subtasks 2-3 complete the reference pair and
block 4-7, which are mutually independent; subtask 8 closes the program.

## Acceptance Criteria

1. All eight subtask specs' acceptance criteria are satisfied and each
   subtask is recorded complete in the decomposition manifest.
2. The structure-conformance test enforces the governed standard for all
   six packs with no exemption list remaining.
3. No pack content references severities outside the shared contract enum;
   all severity closers read "Blocker or Major".
4. The kotlin/kmp pair covers all ten approved review areas between them,
   including the new kmp platform-correctness specialist.
5. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
   `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` all
   pass at program end, with no generated artifacts committed and
   `./install.sh` run.

## Non-Goals

- No changes to the shared severity enum, the specialist report-output
  contract sections, the shell contract version, or any
  `contract_version` value.
- No new approved review-area names beyond the existing taxonomy.
- No `code_review_composition` schema changes; the `kmp-baseline` mode pin
  stays.
- No feature-task add-on surfaces for packs that lack them.
- No boundary-memory (`agent/`) restructuring.

## Constraints

- Authored source is `content.md`; generated wrappers, pointers, and
  provider outputs stay out of source.
- Specialists keep `internal-for` sidecar installation; extra authored
  guidance goes into H2/H3 sections or the documented rubric sidecar.
- The severity enum source of truth remains
  `orchestration/review-orchestrator/specialist-contract.md`.

## Validation Strategy

Per-subtask strategies live in the subtask specs. Program-level gates:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
grep -rn "Critical or Major\|Major or Critical\|Major or Blocker" platform-packs/ && exit 1 || true
```
