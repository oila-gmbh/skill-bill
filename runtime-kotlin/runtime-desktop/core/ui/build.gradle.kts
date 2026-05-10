plugins {
  id("skillbill.kmp-compose")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:designsystem"))
    }

    jvmTest.dependencies {
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
