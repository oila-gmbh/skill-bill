plugins {
  id("skillbill.kmp-compose")
}

compose.resources {
  publicResClass = true
}

kotlin {
  sourceSets {
    jvmTest.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.test)
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
