# SKILL-197 — Subtask 2: Record a disposition for every remaining KMP-uncovered area

## Scope

After subtask 1, `kmp` declares six areas. Four remain owned by `kotlin` on an Android/KMP diff:
`performance`, `security`, `testing`, and `api-contracts`. Each must receive a recorded decision — no
area is left undecided.

For each of the four areas:

- Audit the `kotlin` area content against an Android/KMP diff and identify every rule whose only
  trigger is a technology absent from an Android runtime (Exposed, Spring, Hibernate, JDBC, R2DBC,
  `@Transactional`, `DataSource`, servlet request scope, Jackson, server Ktor, outbox,
  publication/ABI).
- Decide one of two dispositions:
  - **declare on `kmp`** — add the area to `declared_code_review_areas`, `declared_files.areas`,
    `area_metadata`, and `lane_conditions`, with new Android-appropriate content, a native-agent
    registration, and a pointer block, following the shape subtask 1 established; or
  - **retain on `kotlin`** — record an explicit justification that the `kotlin` content is reachable
    and sufficient on an Android/KMP diff.
- Record the disposition durably in the repository, not only in the run transcript.

The measured baseline skew from the parent spec is `security` 4/16, `performance` 3/15,
`testing` 3/19, `api-contracts` 3/16 rule lines. `testing` is the least-skewed area and was the
observed run's most productive lane at 6 findings, so `runTest` virtual time, dispatcher ordering,
and Flow sequence assertions are evidence for retention rather than against it. The audit decides;
these numbers are the starting evidence, not the answer.

## Acceptance Criteria

1. Each of `performance`, `security`, `testing`, and `api-contracts` has a recorded disposition
   committed to the repository — either a `kmp` declaration with Android-appropriate content, or a
   written justification that the `kotlin` content is reachable and sufficient on an Android/KMP
   diff. No area is left undecided.
2. Each recorded justification for retention names the specific `kotlin` rules audited and states why
   the backend-only rules are inert rather than misleading on an Android/KMP diff.
3. For every area this subtask declares on `kmp`, the manifest carries the area in
   `declared_code_review_areas`, `declared_files.areas`, `area_metadata`, and `lane_conditions`; the
   content file exists with the required sections and `contract_version` parity; the specialist is
   registered in the `kmp` native-agent bundle; and a pointer block exists — matching subtask 1's
   shape.
4. For every area this subtask declares on `kmp`, the new content contains no rule whose only trigger
   is a technology absent from an Android/KMP runtime, asserted by the same drift check subtask 1
   extended.
5. For every area this subtask declares on `kmp`, an Android/KMP diff plans exactly one lane for that
   area owned by `kmp`, and a backend-dominant Kotlin diff plans the same area owned by `kotlin`.
6. The `kotlin` pack's `declared_code_review_areas`, area content, `area_metadata`, and
   `lane_conditions` are byte-unchanged. A backend Kotlin review plans the same lanes with the same
   rubrics as before SKILL-197.
7. Area coverage is unchanged: the set of distinct areas planned for an Android/KMP diff is identical
   before and after this subtask. Only owning pack and content may change.
8. No `generic` lane is reintroduced for any area covered by this spec.
9. Every pinned area-set expectation updated in subtask 1 is updated again for any area newly
   declared here, and `(cd runtime-kotlin && ./gradlew check)` plus `skill-bill validate` pass.
10. No comments are added to any changed file.

## Non-Goals

- Restructuring `kotlin` into a backend pack plus a neutral Kotlin core. If the audit finds all four
  areas unreachable, record that finding and stop; the split is a separate ticket, per the parent
  spec's non-goals.
- Changing routing, lane selection, or fallback semantics. That is SKILL-196.
- Bundling areas into fewer lanes, tightening `lane_conditions` triggers, or adding a pre-fan-out
  triage pass.

## Dependency Notes

Depends on subtask 1 (required, not optional). Subtask 1 establishes the manifest wiring, content
house style, native-agent registration, pointer block, and backend-drift check that this subtask
reuses for each area it declares. Running this subtask first would duplicate that scaffolding.

## Validation Strategy

- `skill-bill validate` for every newly declared area's schema, declared-file existence, required
  sections, and `contract_version` parity.
- `(cd runtime-kotlin && ./gradlew check)` for updated pinned area sets, native-agent parity, the
  backend-drift check over each new rubric, and the ownership regression assertions.
- A diff of `platform-packs/kotlin/` proves AC 6: the audit must produce no change under that path.

## Next Path

SKILL-197 is complete. SKILL-196 becomes unblocked, including its AC 4 generic-lane exclusion.
