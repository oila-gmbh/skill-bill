# Skill Bill IntelliJ plugin

Isolated IntelliJ Platform plugin that will surface Skill Bill feature-work status
in the IDE. This directory is a **sibling** of `runtime-kotlin/` with its own
Gradle build — it is not included in the runtime build and adds no IntelliJ
dependency there.

See [ARCHITECTURE.md](ARCHITECTURE.md) for package ownership, persistence policy,
source-of-truth rules, and the future tool-window extension path.

## Requirements

- JDK 21
- IntelliJ IDEA 2025.2–2026.1 (Community or Ultimate) for `runIde`
- A `skill-bill` CLI on `PATH`, or a preference override to the executable

## Common tasks

```bash
cd intellij-plugin
./gradlew check          # unit + architecture tests (no IDE fixture)
./gradlew buildPlugin    # package the plugin archive
./gradlew runIde         # launch a sandbox IDE with the plugin
./gradlew verifyPlugin   # Plugin Verifier against 2025.2 and 2026.1 baselines
```

Configuration cache is enabled via `gradle.properties`
(`org.gradle.configuration-cache=true`).

## CLI resolution

1. Optional preference override (`SkillBillApplicationSettings.cliExecutableOverride`)
2. Else `PATH` lookup of `skill-bill`

Missing or unusable executables become typed unavailable/misconfigured domain
outcomes — never stack traces in the UI.

## What this subtask does not include

- Status-bar widget registration/rendering (subtask 3)
- Tool window / Compose UI
- Marketplace publish or signing
- Workflow mutation
- Remote Development / Split Mode (documented as deferred)

## Compatibility

| Item | Value |
| --- | --- |
| Plugin id | `dev.skillbill.intellij` |
| Products | IntelliJ IDEA Community, Ultimate |
| Builds | `252`–`261.*` (IDEA 2025.2 through 2026.1) |
| Platform Gradle Plugin | 2.x |
| JVM | 21 |
