# SKILL-82 Subtask 3 — Smoke test and README accuracy

## Scope

Write an automated offline smoke test that exercises the full headless bootstrap path, and
verify that the README Quickstart one-liner is accurate (i.e. works without a prior clone).

## Acceptance Criteria

1. A smoke test script (in `scripts/` or as a CI job in `.github/workflows/`) runs
   `install.sh` against a temp `HOME` directory with no prior clone, using
   `SKILL_BILL_RELEASE_DIR` to supply a pre-built skills bundle and runtime zips offline.
   The test asserts: exit code 0, `skill-bill version` succeeds when called with the
   installed launcher, and at least one skill file exists under the temp agent directory.
2. The smoke test covers the `--clean` flag: a second run with `--clean` completes with exit
   code 0 and the skill directory reflects the bundle contents (not a mix of old + new).
3. The smoke test covers the `--prefer-upstream` flag on conflict: seed a skill with a local
   edit, re-run with `--prefer-upstream`, assert the upstream version wins.
4. The README Quickstart section accurately describes the one-liner as working on a fresh
   machine. No stale clone-first instructions remain in the primary install path.
5. The feature branch is merged to main and the PR references SKILL-82.

## Non-goals

- Live network tests against the real GitHub Releases API. All assertions use the offline
  `SKILL_BILL_RELEASE_DIR` seam.
- Windows-native smoke test. macOS and Linux coverage is sufficient.

## Dependencies

- Subtask 2 must be complete (bootstrap logic and `--clean` flag body implemented).

## Validation strategy

Run the smoke test locally. Confirm all three scenarios (fresh install, --clean, --prefer-upstream
conflict) pass. Run `./gradlew check` from `runtime-kotlin/` to confirm no regressions.

## Next path

Open the parent PR for SKILL-82 once the smoke test passes.
