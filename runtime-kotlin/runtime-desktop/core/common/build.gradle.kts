plugins {
  id("skillbill.kmp-library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(libs.kotlinx.coroutines.core)
    }

    jvmTest.dependencies {
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
