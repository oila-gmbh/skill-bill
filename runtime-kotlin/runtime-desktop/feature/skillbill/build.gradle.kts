plugins {
  id("skillbill.kmp-compose")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:common"))
      implementation(project(":runtime-desktop:core:designsystem"))
      implementation(project(":runtime-desktop:core:domain"))
      implementation(project(":runtime-desktop:core:ui"))
      implementation(libs.kotlinx.coroutines.core)
    }

    jvmTest.dependencies {
      implementation(project(":runtime-desktop:core:testing"))
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
