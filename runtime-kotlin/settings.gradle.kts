pluginManagement {
  includeBuild("build-logic")

  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

rootProject.name = "runtime-kotlin"

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
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
  "runtime-desktop",
  "runtime-desktop:core:common",
  "runtime-desktop:core:data",
  "runtime-desktop:core:database",
  "runtime-desktop:core:datastore",
  "runtime-desktop:core:designsystem",
  "runtime-desktop:core:domain",
  "runtime-desktop:core:navigation",
  "runtime-desktop:core:testing",
  "runtime-desktop:core:ui",
  "runtime-desktop:feature:skillbill",
  "runtime-mcp",
  "runtime-ports",
)
