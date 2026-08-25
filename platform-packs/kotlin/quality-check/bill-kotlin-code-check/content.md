---
name: bill-kotlin-code-check
description: Run Gradle check, fix failures, confirm until green. Kotlin pack quality-check sidecar for bill-code-check.
internal-for: bill-code-check
---

# Kotlin Quality Check

## Purpose

Make `./gradlew check --continue` green. Fix root causes. Do not weaken checks.

## Execution Steps

1. Establish files in scope from the requested work unit and `git diff --name-only`.
2. Discover the build file, Gradle wrapper, and CI configuration in `settings.gradle.kts`, module `build.gradle.kts` files, convention plugins, `gradle/libs.versions.toml`, `gradlew`, and repository scripts, in that order, before falling back to a conventional Gradle entrypoint.
3. Run the pack's quality-check entrypoint as the pack `validation_gate.collect_all_full_gate_command` exactly: `./gradlew check --continue` from the Gradle project root that owns the wrapper (this repository: `runtime-kotlin`).
4. Read the full output and collect every failure.
5. Fix those failures at their root cause. Targeted compile, test, and module checks are allowed only while repairing a known finding.
6. Run `./gradlew check --continue` again to confirm.
7. If everything is green, stop. If anything failed, go back to step 3 with that output as the finding set.
8. Attribute each failure to scoped work, pre-existing state, environment, or maintainer-owned configuration.

## Fix Strategy

Use this priority-ordered fix ladder:

1. Fix every failure from the last collect-all at its root cause.

Never suppress a failure with annotations, baselines, disabled rules, or skipped tests.

### Repair Window

Run `./gradlew check --continue`, read the output, fix every finding, then re-run the same command to confirm. Do not invoke the full collect-all gate after each individual finding. If the confirm fails, that output is the new finding set — loop until green. When a cache-bypassing confirm is required, use the pack `validation_gate.cache_bypassing_collect_all_full_gate_command`.

Run the full suite when targeted checks cannot establish safety.

Report a blocker with the exact command, owned failure, attempted diagnosis, and required maintainer decision when credentials, unavailable infrastructure, or out-of-scope repository state prevents completion.
