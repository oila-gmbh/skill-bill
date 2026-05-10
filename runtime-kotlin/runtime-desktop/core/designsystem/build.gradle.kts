plugins {
  id("skillbill.kmp-compose")
}

kotlin {
  sourceSets {
    jvmTest.dependencies {
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
