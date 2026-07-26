# SKILL-52.3 Subtask 5 - Enforcement Hardening + External-Schema Decision + Final Lock

Parent spec: [.feature-specs/SKILL-52.3-runtime-hexagon-leak-closure/spec.md](./spec.md)
Issue key: SKILL-52.3
Subtask order: 5 of 5
Depends on: subtasks 1, 2, 3, 4
Branch model: same-branch, commit per subtask

## Purpose

Close the gaps in the architecture-test enforcement layer so the leaks fixed by
subtasks 1-4 cannot silently recur, record the external schema source-of-truth
coupling as an explicit decision, and lock all documentation (`ARCHITECTURE.md`,
`agent/decisions.md`, `agent/history.md`, `RuntimeModule`) to the final rules.

## Scope

In scope — enforcement hardening:

- Source-text scanning for package-boundary rules. The current
  `assertNoBannedImports` matches parsed `import` statements only, so a
  fully-qualified inline reference (e.g. `skillbill.infrastructure.fs.Foo()`
  with no import) is invisible. Add a source-text scan (analogous to
  `assertNoBannedSourceReferences`) for the `runtime-domain` / `runtime-ports` /
  `runtime-application` infra/entrypoint bans so inline FQN references are caught.
- Desktop scanner coverage. Add
  `runtime-desktop/core/data/src/jvmMain/kotlin` (and, if cheap,
  `feature/skillbill/src/jvmMain/kotlin`) to the `sourceRoots` list in
  `RuntimeArchitectureTest.kt` so the central import/raw-map scanner sees the
  desktop source set where runtime imports actually live, instead of relying
  solely on the Gradle allow-list test.
- Validator-import ban. Add/extend a test so `*SchemaValidator` and
  `*CoherenceValidator` imports are banned from:
  - `runtime-domain/.../skillbill/install/` (the gap that let the install leak
    through), in addition to the existing `skillbill/workflow/` ban; and
  - `runtime-application` main source.
- Contracts purity guard. Add a test asserting `runtime-contracts` main source
  contains no `com.networknt.*`, `com.fasterxml.jackson.*`, or
  `java.nio.file.Files`, mirroring the relocation done in subtask 1.

In scope — external schema source-of-truth:

- Record a decision in `agent/decisions.md` covering the
  `../orchestration/contracts/*.yaml` source-of-truth that is copied into
  `runtime-infra-fs` (after subtask 1) and `runtime-mcp` at build time: why the
  canonical schemas live outside the Gradle project, the parity guarantee, and
  the loud-fail behavior if a file is missing.
- Ensure a parity test covers each `*_CONTRACT_VERSION` constant against its
  schema file's `contract_version.const` (extend the existing platform-pack
  parity test pattern to install-plan / workflow-state / decomposition /
  telemetry-event if not already covered).

In scope — documentation lock:

- Update `runtime-kotlin/ARCHITECTURE.md`:
  - `runtime-contracts` description: remove "runtime/schema parse-seam
    validators" and "schema resource copy tasks"; state contracts is
    DTO/constants/exceptions only.
  - `runtime-infra-fs` description: add ownership of the three relocated schema
    validators and the coherence validator + schema copy tasks.
  - Boundary Rule set: add the rule that no schema validator / Jackson /
    networknt / `Files` may live in `runtime-contracts`, and that domain/
    application reach validation only through ports.
  - Reduce the `open-boundary-allowlist` block to the reconciled set from
    subtasks 3-4.
- Update `agent/decisions.md` with: validator relocation (superseding the
  2026-05-18 and 2026-05-24 dual-validation decisions), domain effect-purity,
  scaffold typed-result closure, open-boundary reconciliation outcomes, and the
  external-schema coupling decision.
- Add an `agent/history.md` entry per the boundary-history hygiene rules.
- Update `RuntimeModule` / any module-metadata that names validator ownership.

Out of scope:

- Behavior changes — this subtask is enforcement, parity, and docs only.
- New Gradle modules.

## Acceptance Criteria

1. Package-boundary bans for `runtime-domain`/`runtime-ports`/
   `runtime-application` are enforced by source-text scan as well as import
   parsing; a synthetic fixture with an inline FQN reference fails the test.
2. `RuntimeArchitectureTest.sourceRoots` includes
   `runtime-desktop/core/data/src/jvmMain/kotlin`; the import/raw-map scanners
   run over the desktop data gateway source set.
3. A test bans `*SchemaValidator`/`*CoherenceValidator` imports from
   `runtime-domain/.../skillbill/install/`, `.../skillbill/workflow/`, and
   `runtime-application` main source, and fails on a synthetic fixture.
4. A test asserts `runtime-contracts` main source is free of networknt,
   Jackson, and `java.nio.file.Files`.
5. `agent/decisions.md` records the external-schema coupling and the parity
   guarantee; a parity test maps each contract-version constant to its schema
   file's declared version.
6. `ARCHITECTURE.md`, `agent/decisions.md`, `agent/history.md`, and
   `RuntimeModule` reflect the final ownership and the reduced allow-list; all
   `ARCHITECTURE.md` <-> test parity assertions pass.
7. Full validation passes:
   - `(cd runtime-kotlin && ./gradlew check)`
   - `skill-bill validate`
   - `scripts/validate_agent_configs`
   - `npx --yes agnix --strict .`

## Validation

```bash
(cd runtime-kotlin && ./gradlew :runtime-core:test --tests 'skillbill.architecture.*')
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
```

## Implementation Notes

- Every new ban test must ship with a synthetic-fixture self-test (the suite
  already follows this convention, e.g. `raw map violation scanner fires on
  known violation fixtures`, `SKILL-52_2 inventory parser fires on synthetic
  fixture`). Add the positive-control fixture so the guard is proven to fire.
- The `RuntimeImplementationImportRules.kt` helper (`isRuntimeImplementationImport`)
  and its self-test are the natural home for the validator-import ban logic;
  extend them rather than adding a parallel scanner.
- When updating `ARCHITECTURE.md`, run the documentation parity tests
  (`RuntimeArchitectureDocumentationTest`, `RuntimeImplementationImportRules`'
  `ARCHITECTURE.md` assertions, and the `open-boundary allow-list documents
  required exceptions` parity test) iteratively — they assert exact substrings
  and exact set equality, so the doc and the test constants must move together.
- This is the only subtask that runs the full `./gradlew check` +
  `skill-bill validate` + `agnix --strict` gate; budget for cross-module
  golden/test churn surfaced by subtasks 1-4.
