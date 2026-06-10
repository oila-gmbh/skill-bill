---
status: In Progress
parent_spec: ./spec.md
subtask_id: 2
---

# SKILL-76 · Subtask 2 — Baseline + reconcile-on-reinstall + interactive conflict

Parent overview: [spec.md](./spec.md). Builds on subtask 1's copied source tree and
wipe-exemption seam. This subtask owns the reconciliation crux: per-skill baseline
manifest, upstream-vs-baseline-vs-local hash comparison, adopt / keep-local /
conflict policy, and the interactive both-changed prompt with pre-swap abort.

## Scope

1. **Reuse the existing content-hash util — DO NOT add a second hashing scheme.**
   Reconciliation MUST compare hashes produced by `computeInstallContentHash`
   (`InstallStaging.kt:127-162`, SHA-256 over rel-path+bytes of authored files +
   pointer specs + inlined support-pointer bytes, truncated to 8 bytes / 16 hex —
   the same hash that keys the `{slug}-{hash}` staging leaf).

2. **Reconcile subcommand in runtime-cli (PREFERRED architecture).** Add a runtime
   subcommand (e.g. `skill-bill ... reconcile`, or fold into `apply`/`plan`) that,
   given the upstream clone source + the copied `~/.skill-bill` source + the
   baseline manifest, computes per-skill upstream/local/baseline hashes and emits a
   MACHINE-READABLE reconciliation plan: per skill → `adopt` (local == baseline:
   take new upstream, refresh baseline), `keep-local` (local != baseline, upstream
   == baseline), `conflict` (local != baseline AND upstream != baseline),
   `new-upstream` (no baseline: copy in + baseline), `locally-authored` (no upstream
   counterpart: preserve, never delete, report). Respect the hexagonal rule: FS IO
   only in `install.sh` shell or `runtime-infra-fs` adapters reached via a domain
   port — NEVER in domain/application. Model the reconciliation result as a typed
   domain model; the hash-compare logic should be unit-testable in
   `runtime-infra-fs`. Loud-fail typed errors — reconciliation aborts, never silent.

3. **Baseline manifest persistence.** Store `$HOME/.skill-bill/skill-baselines.json`
   mapping skill-relative-path → last-copied-in upstream content hash, written
   through a `runtime-infra-fs` adapter behind a domain port (no FS IO in
   domain/application). Refresh on `adopt` / `new-upstream` / accepted-`conflict`;
   never churn on a no-change reinstall. Coordinate with subtask 1's wipe-exemption
   so the manifest survives the pre-install wipe.

4. **Interactive conflict in install.sh (AC-7, REVISED).** The shell drives the UX
   from the subcommand's machine-readable conflict report:
   - both-changed conflict → WARN + PROMPT. `accept` = overwrite local with upstream
     + refresh baseline; `abort` = stop the WHOLE install, change nothing.
   - NO-TTY (CI / piped install) → any conflict ABORTS with a clear message (no
     silent choice).
   - ALL conflicts reported in the install summary. No sidecar.
   - CRITICAL ORDERING: conflict detection + the prompt/abort decision happen BEFORE
     the atomic source swap commits, so an abort leaves the EXISTING install fully
     intact (no half-applied state). Sequence the copy-in so reconciliation runs
     against a staged candidate and the `mv`/swap only happens after the decision.

5. **Apply outcomes per skill.** Wire reconcile outcomes into the copy/swap so:
   adopt → new upstream lands; keep-local → local copy retained, baseline untouched;
   new-upstream → copied + baselined; locally-authored → preserved + reported;
   accepted-conflict → upstream lands + baseline refreshed; aborted-conflict →
   nothing changes.

## Acceptance Criteria (this subtask)

- AC-3 (remaining): a subsequent `./install.sh` from the copied tree SUCCEEDS
  without the clone present (or the clone requirement is explicit + documented).
- AC-5: user edit under `~/.skill-bill` survives reinstall (local != baseline,
  upstream == baseline → keep local).
- AC-6: unmodified local skill (local == baseline) adopts new upstream + refreshes
  baseline.
- AC-7: both-changed conflict → WARN + PROMPT; accept overwrites + refreshes
  baseline; abort stops whole install, changes nothing; NO-TTY aborts with clear
  message; all conflicts in summary; detection BEFORE the atomic swap. No sidecar.
- AC-8: new upstream skills copied + baselined; locally-authored skills with no
  upstream counterpart preserved (never deleted) + reported.
- AC-9: reconciliation idempotent — no-change reinstall produces no diffs, no
  spurious conflicts, no baseline churn.

## Validation (this subtask)

- `bill-code-check`; `cd runtime-kotlin && ./gradlew check`.
- New/updated tests:
  - `runtime-infra-fs` (`InstallStagingTest` / new reconcile test, reusing
    `InstallApplyTestSupport`): unit-test the hash-compare → reconciliation-plan
    outcomes (adopt / keep-local / conflict / new-upstream / locally-authored)
    using `computeInstallContentHash`; idempotency (same inputs → empty plan, no
    baseline churn).
  - `runtime-core` `InstallerShellDelegationTest` (real `install.sh` over fixtures):
    reinstall preserves a user-edited skill; reinstall adopts upstream for an
    untouched skill; both-changed conflict accept overwrites + reports; both-changed
    conflict abort leaves the prior install fully intact (no half-applied state);
    no-TTY conflict aborts with a clear message; reinstall without the clone present
    succeeds; idempotent no-change reinstall.
- `npx --yes agnix --strict .`; `scripts/validate_agent_configs`.

## Non-goals (this subtask)

- Copy-in mechanics, arg repoint, wipe-exemption (subtask 1 — depended upon).
- Full AC-10 dangling-link migration assertions + SKILL-74/75 parity (subtask 3).
- Three-way auto-merge; portable export/import; versioned per-skill registry.

## Dependencies

- Subtask 1 (copy-in + repoint + wipe-exemption seam + baseline-manifest location).
  Required — reconciliation needs the durable copied source + a surviving manifest
  slot to compare against.

## Handoff

Run `bill-feature-task` on
`.feature-specs/SKILL-76-self-contained-skill-source/spec_subtask_2_baseline-reconciliation.md`.
