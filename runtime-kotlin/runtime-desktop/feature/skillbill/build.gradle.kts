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
      // SKILL-47 F-008: PlatformPackSchemaViewerStateTest references the canonical schema path
      // constant (`PlatformPackSchemaPaths.REPO_RELATIVE_PATH`) in runtime-core so the path
      // appears once in the codebase. Production code in this feature module does NOT depend on
      // runtime-core; this is a jvmTest-only dependency.
      implementation(project(":runtime-core"))
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
