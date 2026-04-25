pluginManagement {
  includeBuild("build-logic")

  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

rootProject.name = "runtime-kotlin"

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { mavenCentral() }
}

include(
  "runtime-application",
  "runtime-contracts",
  "runtime-core",
  "runtime-domain",
  "runtime-infra-fs",
  "runtime-infra-http",
  "runtime-infra-sqlite",
  "runtime-cli",
  "runtime-mcp",
  "runtime-ports",
)
