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
