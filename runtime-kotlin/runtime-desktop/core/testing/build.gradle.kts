plugins {
  id("skillbill.kmp-library")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:datastore"))
      implementation(project(":runtime-desktop:core:navigation"))
      implementation(project(":runtime-desktop:core:domain"))
    }

    jvmTest.dependencies {
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}
