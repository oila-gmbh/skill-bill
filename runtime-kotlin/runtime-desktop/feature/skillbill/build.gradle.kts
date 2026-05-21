plugins {
  id("skillbill.kmp-compose")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:common"))
      implementation(project(":runtime-desktop:core:designsystem"))
      implementation(project(":runtime-desktop:core:datastore"))
      implementation(project(":runtime-desktop:core:domain"))
      implementation(project(":runtime-desktop:core:ui"))
      implementation(libs.kotlinx.coroutines.core)
    }

    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test)
      implementation(project(":runtime-desktop:core:testing"))
      // SKILL-48 C8: consume the shared `repoRootFromTest()` helper published by runtime-core
      // via the `java-test-fixtures` plugin so the prior copies in jvmTest collapse into one. The
      // KMP DSL does not expose the `testFixtures()` shorthand; depend on the published capability
      // explicitly instead.
      implementation(project(":runtime-core")) {
        capabilities {
          requireCapability("dev.skillbill:runtime-core-test-fixtures")
        }
      }
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
