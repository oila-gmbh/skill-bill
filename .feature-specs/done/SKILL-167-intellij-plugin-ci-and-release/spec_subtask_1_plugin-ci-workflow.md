# SKILL-167 · Subtask 1 — Plugin CI workflow

## Scope

Give `intellij-plugin/` its first CI coverage: a new dedicated workflow file (e.g.
`.github/workflows/plugin-ci.yml`) that runs the plugin's own Gradle `check` on every change
touching the plugin, plus a scheduled nightly `verifyPlugin` job. `validate-agent-configs.yml`,
`install-smoke-test.yml`, and `release.yml` are not modified.

### Design decision: separate file, hosted runner only

The workflow is a new separate file, not a job added to `validate-agent-configs.yml`
(spec AC 1 mandates this). The plugin is pure JVM with no macOS-specific behavior, so every
job runs on `ubuntu-latest` only. This satisfies the fork-safety posture of
`validate-agent-configs.yml:16-34` by construction: no job ever targets the self-hosted
`macmini` runner, so untrusted fork-PR code cannot reach it and no conditional runner-matrix
setup job is needed.

### Design decision: triggers and path filter

- `pull_request` and `push: branches: [main]`, both gated on
  `paths: ['intellij-plugin/**', '.github/workflows/plugin-ci.yml']` so a runtime-only PR
  never pays for an IntelliJ Platform download.
- A `concurrency` group keyed on workflow + ref with `cancel-in-progress: true`, mirroring
  `validate-agent-configs.yml:11-13`.
- A separate `schedule` (nightly cron) trigger for the `verifyPlugin` job. Schedule events
  ignore path filters, which is fine — the nightly job exists precisely to run against
  whatever is on `main`.

### Design decision: job layout

Two jobs in the one file:

1. `check` — runs on `pull_request` and `push`; steps: checkout, `setup-java` with Temurin
   JDK 21 (the plugin toolchain per `intellij-plugin/build.gradle.kts:14-16`), Gradle caching
   (either `setup-java`'s `cache: gradle` or `gradle/actions/setup-gradle`), then
   `(cd intellij-plugin && ./gradlew check)`. Always the plugin's own wrapper, never
   `runtime-kotlin/gradlew`.
2. `verify` — runs only on `schedule` (and stays available for the plugin release workflow of
   subtask 3 to replicate); same JDK/caching setup, then
   `(cd intellij-plugin && ./gradlew verifyPlugin)`. `failureLevel` stays exactly as declared
   in `intellij-plugin/build.gradle.kts:61-66` — the workflow passes no override. `verifyPlugin`
   must NOT run on `pull_request` or `push`: it downloads IC 2025.2.5 and IDEA 2026.1 on top
   of the compile baseline (`build.gradle.kts:55-60`).

Use an `if: github.event_name == 'schedule'` guard (or trigger-disjoint job conditions) so the
two jobs never bleed into each other's events.

### Design decision: nightly failure visibility

A failed scheduled run surfaces as a red run on `main` in the Actions tab, and GitHub notifies
via its standard scheduled-workflow failure notification. That is the maintainer-visible
surface required by the parent spec's constraint; no additional alerting integration is in
scope.

## Acceptance Criteria

1. A new workflow file exists (separate from `validate-agent-configs.yml`) that runs
   `cd intellij-plugin && ./gradlew check` on pull requests and pushes to `main`, gated on
   `paths: ['intellij-plugin/**']` plus the workflow's own file path.
2. The `check` job provisions JDK 21 via `setup-java` and has Gradle caching enabled.
3. Every job in the workflow runs on `ubuntu-latest`; no job can execute on the self-hosted
   runner for any event, fork PR or otherwise.
4. `verifyPlugin` does not run on `pull_request` or `push` events; it runs on a scheduled
   nightly cron with `failureLevel` unchanged from `intellij-plugin/build.gradle.kts:61-66`.
5. A deliberately broken plugin test fails the new workflow's `check` job — demonstrated on
   the feature branch's PR with a temporary commit that is reverted after the red run is
   observed.
6. A change touching only `runtime-kotlin/**` does not trigger the workflow — demonstrated
   (e.g. via the PR's Actions run list or `gh run list` on a runtime-only commit) before the
   feature is done.
7. Workflow YAML is syntactically valid (e.g. `actionlint` or a successful triggered run).

## Non-Goals

- No plugin release workflow, tag handling, or artifact publishing — subtask 3.
- No changes to `validate-agent-configs.yml`, `install-smoke-test.yml`, or `release.yml`.
- No macOS or self-hosted coverage for the plugin.
- No change to plugin code, tests, `failureLevel`, or the compatibility range.
- No Marketplace, signing, or `publishPlugin` wiring.

## Dependency Notes

- Independent of subtasks 2 and 3; safe to implement first.
- No dependency on SKILL-166: the plugin already builds on JDK 21 independently of the
  runtime toolchain.
- Subtask 3's release workflow will also run `verifyPlugin`; keep the verify steps easy to
  mirror (identical setup-java + Gradle invocation) but do not pre-build shared abstractions
  for it.

## Validation Strategy

- CI-side evidence only; do not run Gradle builds locally in implement/review phases. The
  demonstrations in AC 5 and AC 6 happen through pushed commits on the feature branch and are
  observed via GitHub Actions (`gh run list` / `gh run watch`).
- Lint the workflow file (`actionlint`) as the cheap static gate.
- Confirm by inspection (and via the AC 6 run list) that a runtime-only diff does not match
  the path filter.

## Next Path

Subtask 2 hardens the installer's and update-check's latest-release resolution so the
plugin tag stream introduced in subtask 3 can never be mistaken for a runtime release.
