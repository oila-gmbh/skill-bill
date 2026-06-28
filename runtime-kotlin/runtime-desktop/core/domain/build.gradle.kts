plugins {
  id("skillbill.kmp-library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:common"))
      implementation(
        "org.jetbrains.compose.components:components-resources:${libs.versions.compose.get()}",
      )
    }

    jvmTest.dependencies {
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
