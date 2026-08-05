---
issue_key: SKILL-136
subtask_id: 2
name: Author the persistence and reliability specialists
parent_spec: .feature-specs/SKILL-136-android-native-review-specialists/spec.md
---

# Subtask 2 — Author the persistence and reliability specialists

## Intended Outcome

Two new governed specialists exist under `platform-packs/kmp/code-review/`,
written for Android-native frameworks in the iOS pack's framework-specific
style, with no backend-JVM rubric.

## Scope

- Scaffold `bill-kmp-code-review-persistence` and
  `bill-kmp-code-review-reliability` through the existing scaffolder
  (`skill-bill new-addon` / `create-and-fill` per the pack's existing pattern)
  rather than hand-assembling boilerplate.
- Author each `content.md` with `Focus`, `Ignore`, `Applicability`, and
  `Project-Specific Rules` sections.
- `persistence` covers: Room transaction and dispatcher boundaries, migration
  safety and destructive-migration fallbacks; SQLDelight transaction and
  driver-thread correctness; DataStore (Preferences and proto) write atomicity
  and concurrent-write races; offline-first sync idempotency keys,
  delta/watermark cursor advancement, and coupling through shared
  cross-feature tables.
- `reliability` covers: WorkManager and `CoroutineWorker` retry, backoff, and
  constraint correctness; foreground-service and process-death recovery for
  long-running sync; `viewModelScope` and `SupervisorJob` collector death from
  an uncaught exception silently disabling a recurring trigger;
  connectivity-aware retry and failure telemetry.

## Acceptance Criteria

1. `platform-packs/kmp/code-review/bill-kmp-code-review-persistence/content.md`
   exists with non-placeholder `Focus`, `Ignore`, `Applicability`, and
   `Project-Specific Rules` sections.
2. `platform-packs/kmp/code-review/bill-kmp-code-review-reliability/content.md`
   exists with the same four non-placeholder sections.
3. The persistence rubric names Room, SQLDelight, and DataStore and covers
   migration safety, write atomicity, and offline-first cursor/idempotency
   correctness.
4. The reliability rubric names WorkManager/`CoroutineWorker` and covers
   retry/backoff/constraints, process-death and foreground-service recovery,
   and collector-death from uncaught exceptions.
5. Neither file references Exposed, Spring `@Transactional`, Hibernate, JDBC,
   R2DBC, broker ack/offset semantics, or `resilience4j`.
6. Each Blocker or Major rule names a concrete data-loss, consistency, or
   availability failure scenario rather than a style preference.
7. Both directories contain `content.md` only; no generated `SKILL.md`
   wrapper, support pointer, or provider-specific native-agent output is
   committed.
8. `skill-bill validate` passes and both specialists render successfully via
   `skill-bill render`.

## Non-Goals

- Declaring the areas in `platform.yaml` or adding routing-table rows
  (Subtask 3).
- Changing routing signals or tie-breakers (Subtask 1).
- Restating the generic pack's technology-neutral kernel rules.

## Dependencies

None. Runs in parallel with Subtask 1.

## Validation Strategy

Assert via test that the new rubric text contains Room/DataStore/WorkManager
terms and omits Exposed/Hibernate/JDBC/R2DBC/`resilience4j` terms. Then:

```bash
skill-bill validate
skill-bill render bill-kmp-code-review-persistence
skill-bill render bill-kmp-code-review-reliability
(cd runtime-kotlin && ./gradlew check)
```

## Next Path

Continue the goal; Subtask 3 declares and routes to these specialists.
