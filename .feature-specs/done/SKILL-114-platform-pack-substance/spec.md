# SKILL-114 - Full-Substance Platform Packs

Status: decomposed; execution not started.

## Outcome

Every maintained platform pack earns its platform name through complete
effective review coverage, detailed platform-specific failure-mode guidance,
a real quality-check path, and measurable separation from other packs.
Universal review discipline is owned once by shared orchestration; pack
content is reserved for language, runtime, framework, toolchain, UI,
accessibility, persistence, security, performance, and operational knowledge
that changes how an expert reviews that platform.

The program upgrades all eight maintained packs: go, ios, kmp, kotlin, php,
python, rust, and typescript. It follows SKILL-112's structural parity work by
governing substance rather than adding another formatting-only standard.

## Findings Basis

The 2026-07-11 cross-pack audit found that every pack passes structural
validation, but content depth and originality vary materially:

- normalized five-word duplication is 81.4% for go and 84.8% for php;
  corresponding go/php rubrics average 89.1% token similarity
- rust/typescript corresponding rubrics average 49.4% similarity and retain
  scaffold-like placeholder rules
- ios is the strongest standalone platform-specific pack
- kmp has a thin core but meaningful effective depth through the kotlin
  baseline and twelve Android add-ons
- kotlin and python are specific but thinner; kotlin lacks standalone UI and
  UX/accessibility coverage
- kmp has no quality-check skill and relies on the explicit kotlin fallback
- current validators enforce structure and minimal imperative vocabulary, but
  not depth, distinctness, ecosystem coverage, or tested platform expertise

## Definition Of Full Substance

A pack is full when all of the following hold:

1. Its effective review surface covers every approved area that can materially
   apply to the platform. A maintained general-purpose language pack covers all
   ten approved areas. A compositional overlay may inherit areas only through
   an explicit required baseline and must add its platform-specific deltas.
2. Each specialist's effective rubric contains at least three coherent
   platform-specific failure-mode clusters and at least ten substantive,
   enforceable review rules. Shared output/ceremony text and the canonical
   severity closer do not count. An unconditionally loaded governed rubric
   sidecar may count; conditional add-ons do not replace core coverage.
3. Each specialist names concrete language/runtime APIs, framework or
   toolchain mechanisms, and observable failure consequences. Generic
   placeholders such as `` `<Platform> hot-path and resource APIs` `` are
   forbidden.
4. Baseline routing maps real diff signals to every effective specialist,
   explains mixed-platform ownership, and scopes generated/vendored code.
5. The quality-check path discovers repository-owned commands, covers the
   platform's compiler/static-analysis/test/security/build ecosystem, maps
   failures to safe fixes, and defines targeted-to-full escalation. Every
   maintained pack has its own declared quality-check skill; KMP's historical
   Kotlin fallback is retired after a KMP checker lands.
6. Pack-authored content stays measurably distinct. After documented
   normalization, no pack has more than 35% shared five-word sequences and no
   pair of corresponding authored rubrics exceeds 65% similarity. Generated
   ceremony, frontmatter identifiers, headings, and canonical closers are
   excluded from the measurement.
7. Universal guidance is removed from pack-local prose only after it is
   present in a generated shared contract consumed by every affected skill.
   Platform-specific behavior remains in manifest-declared platform packs.

## Decomposition

1. **Substance standard and audit gate** — govern the definition above,
   implement reproducible depth/distinctness reporting, extract genuinely
   universal review discipline, and add acceptance/rejection fixtures.
2. **Go pack depth** — replace shared backend prose with Go runtime,
   concurrency, net/http, storage, tooling, UI, accessibility, and operational
   failure modes; deepen its quality checker.
3. **PHP pack depth** — independently rebuild PHP guidance around PHP runtime
   semantics and major framework/tooling ecosystems, eliminating the Go clone.
4. **Python pack depth** — expand Python interpreter, typing, async, packaging,
   framework, data, UI, accessibility, and operational coverage.
5. **Rust pack depth** — replace scaffold placeholders with ownership, unsafe,
   async, FFI, feature, cargo, UI, persistence, and production failure modes.
6. **TypeScript pack depth** — replace scaffold placeholders with type/runtime
   boundary, Node/browser, package, framework, UI, accessibility, data, and
   operational failure modes.
7. **Kotlin pack depth** — deepen JVM/server/library guidance and add standalone
   Kotlin UI and UX/accessibility specialists so the pack covers all ten areas.
8. **KMP pack depth** — preserve Kotlin composition, deepen multiplatform and
   target-specific deltas, rationalize add-ons, and add a KMP quality checker.
9. **iOS pack depth** — retain existing mature rules while closing ecosystem,
   modern SDK, tooling, UI/accessibility, and quality-check gaps.
10. **Cross-pack conformance and release gate** — remove temporary baselines,
    prove every pack passes substance and duplication gates, refresh docs and
    installs, and record final metrics.

Subtask 1 blocks every pack elevation. Subtasks 2-7 and 9 may proceed
independently after it. Subtask 8 additionally depends on the Kotlin elevation.
Subtask 10 depends on all pack subtasks.

## Acceptance Criteria

1. All ten subtask specs' acceptance criteria are satisfied and every subtask
   is recorded complete in the decomposition manifest.
2. A governed pack-substance standard defines effective coverage, minimum
   specialist depth, platform specificity, quality-check depth, composition,
   and duplication measurement without requiring identical physical layouts.
3. Every maintained general-purpose pack declares all ten approved review
   areas; KMP obtains all ten effectively through its required Kotlin baseline
   and its own platform-correctness, UI, and UX/accessibility deltas.
4. Every maintained pack declares its own quality-check content; KMP-specific
   Gradle, Android, target-compilation, lint, test, and shrinker checks no
   longer depend solely on a Kotlin fallback.
5. Every specialist meets the governed effective-rubric minimum of three
   platform-specific failure-mode clusters and ten enforceable rules, with no
   generic platform-API placeholder standing in for actual expertise.
6. The reproducible authored-content audit reports at most 35% shared
   normalized five-word sequences for each pack and at most 65% similarity for
   every pair of corresponding authored rubrics.
7. Go and PHP no longer form a substituted copy: each passes the duplication
   gate and its rules name distinct language/runtime/framework/tooling failure
   mechanisms.
8. Shared orchestration owns universal evidence, severity, output, scoping,
   attribution, and merge discipline; pack content does not repeat those rules
   except where a platform-specific consequence changes them.
9. Pack-specific tests and rejection fixtures prove that shallow,
   placeholder-driven, under-covered, and over-duplicated maintained packs
   fail the new repository quality gate with actionable file/area diagnostics.
10. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
    `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass;
    `./install.sh` refreshes local staging; no generated source artifacts are
    committed.

## Non-Goals

- Do not force third-party or fork-owned packs to use the maintained-pack
  editorial thresholds at runtime; schema/loader validity remains about safe
  contract consumption.
- Do not require identical word counts, identical specialist counts for
  compositional overlays, or one framework choice per language.
- Do not add new approved review-area slugs.
- Do not create a cross-platform backend pack or move backend platform
  behavior into `orchestration/`.
- Do not duplicate full framework manuals, command references, or external
  documentation in prompts.
- Do not change severity or confidence enums.
- Do not weaken authored/generated boundaries or commit generated wrappers,
  pointers, or provider-specific native-agent output.

## Constraints

- `content.md` remains the governed authored skill source, except for the
  existing documented authored-rubric-sidecar contract.
- Platform-specific behavior remains under `platform-packs/<slug>/` and pack
  discovery remains manifest-driven.
- Shared extraction is limited to genuinely platform-independent contracts;
  superficially similar backend rules must be rewritten around their actual
  platform rather than centralized under a misleading generic abstraction.
- Metrics operate on authored review and quality-check prose, not generated
  wrappers or shared pointers, and use one versioned normalization algorithm.
- Quality thresholds are enforced by repository conformance for maintained
  packs, not by hard-coding platform slugs into runtime routing.
- Comments remain subject to the repository comments policy.

## Validation Strategy

Per-pack commands and fixtures live in the subtask specs. Program-level gates:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
```

The final audit output must be captured in the SKILL-114 history entry with
per-pack coverage, rule-depth, placeholder, quality-check, and duplication
results.
