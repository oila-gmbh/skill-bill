# runtime-kotlin

Standalone Kotlin runtime for the local `skill-bill` CLI and MCP-facing primitives.

## Build

```bash
./gradlew build
```

## IntelliJ / Android Studio

`runtime-kotlin` is a nested standalone Gradle project inside the larger `skill-bill` repo.

If you open only the outer `skill-bill` repository as a plain IntelliJ project, Kotlin source files under `runtime-kotlin/src/main/kotlin` may show unresolved `skillbill.*` imports even though Gradle builds successfully.

Use one of these setups instead:

1. Open `runtime-kotlin/` as its own IntelliJ project.
2. If you keep the outer repo open, attach `runtime-kotlin/build.gradle.kts` as a Gradle project and run a Gradle sync.

If imports stay red after attaching the build, reload the Gradle project or restart the IDE.
