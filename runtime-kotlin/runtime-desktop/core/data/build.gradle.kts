plugins {
  id("skillbill.kmp-library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:common"))
      implementation(project(":runtime-desktop:core:database"))
      implementation(project(":runtime-desktop:core:domain"))
      implementation(libs.room3.runtime)
    }

    jvmMain.dependencies {
      implementation(project(":runtime-core"))
    }

    jvmTest.dependencies {
      // SKILL-48 C8: consume the shared `repoRootFromTest()` helper published by runtime-core
      // via the `java-test-fixtures` plugin so this jvmTest can drop its private copy. The KMP
      // DSL does not expose the `testFixtures()` shorthand; depend on the published capability
      // explicitly instead so the test-fixtures classpath is wired in.
      implementation(project(":runtime-core")) {
        capabilities {
          requireCapability("dev.skillbill:runtime-core-test-fixtures")
        }
      }
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.core)
    }
  }
}
