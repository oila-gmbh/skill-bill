# SKILL-80 · Subtask 1 — Detekt validate-gate blocker

## Scope

The validate phase runs `(cd runtime-kotlin && ./gradlew check)`, whose detekt step
is configured with `build.maxIssues: 0` (`runtime-kotlin/config/detekt/detekt.yml`),
so a single violation hard-fails the gate. The three rules that repeatedly blocked
decomposed-goal runs — `MaxLineLength`, `ReturnCount`, `NestedBlockDepth` — all run
on detekt defaults (120 / 2 / 4); none are configured explicitly. Spotless
(`spotlessCheck`, ktlint, max_line_length 120) runs before detekt via
`build-logic/convention/src/main/kotlin/dev/skillbill/runtime/buildlogic/Quality.kt`,
but ktlint does not auto-resolve detekt `ReturnCount` / `NestedBlockDepth`.

The named hotspot `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeEnforcementHardeningArchitectureTest.kt`
currently appears clean, confirming the failures were transient agent-authored code
the strict gate caught mid-run — so this subtask must reduce **recurrence**, not just
delete a few lines.

In scope:
- Make the detekt configuration for these three rules explicit and intentional in
  `detekt.yml` (document chosen thresholds rather than relying on silent defaults).
- Ensure any current real violation in `RuntimeEnforcementHardeningArchitectureTest.kt`
  (and the other SKILL-52.4 files cited in blocked reasons) is resolved, not suppressed.
- Reduce recurrence so routine agent-authored Kotlin does not block the goal validate
  gate on these three rules. Acceptable mechanisms (planning chooses): a committed
  detekt **baseline** strategy scoped to pre-existing issues, narrowly-justified
  threshold adjustments, and/or surfacing detekt findings into the implement/audit
  phase so they are fixed before validate. Auto-suppression via blanket `@Suppress`
  is explicitly disallowed.

## Acceptance Criteria

1. `MaxLineLength`, `ReturnCount`, and `NestedBlockDepth` are explicitly declared in
   `runtime-kotlin/config/detekt/detekt.yml` with intentional thresholds (no reliance
   on implicit defaults for these three rules).
2. `(cd runtime-kotlin && ./gradlew check)` passes on a clean checkout, including
   detekt and spotless, with `build.maxIssues: 0` preserved (the strict gate is kept,
   not weakened to a nonzero issue budget).
3. Any real violation of the three rules in `RuntimeEnforcementHardeningArchitectureTest.kt`
   and the other files named in SKILL-52.4 blocked reasons is fixed at the source
   (not blanket-suppressed); `git grep` shows no new file-wide `@Suppress` added for
   these rules.
4. A documented, repeatable recurrence-reduction mechanism is in place (baseline file,
   justified threshold change, or implement/audit-phase detekt surfacing) and is
   described in this subtask's history entry so a future goal run does not re-block on
   the same three rules.
5. The change does not disable detekt for any module that currently runs it
   (`skillbill.quality` plugin coverage is unchanged).

## Non-goals

- Rewriting unrelated detekt rules or raising `build.maxIssues` above 0.
- Reformatting files unrelated to the cited blocked reasons.
- Changing the validate-phase command itself (handled conceptually in subtask 3/4).

## Dependency notes

Independent. No dependency on subtasks 2–4.

## Validation strategy

- `(cd runtime-kotlin && ./gradlew check)` passes locally.
- If a baseline is introduced, demonstrate that a deliberately-introduced trivial
  `ReturnCount`/`NestedBlockDepth` violation in a scratch file is reported (baseline
  does not blanket-mute new code), then remove the scratch file.

## Next path

```bash
skill-bill goal SKILL-80
```
