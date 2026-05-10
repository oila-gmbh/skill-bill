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
    }

    jvmTest.dependencies {
      implementation(project(":runtime-desktop:core:testing"))
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
