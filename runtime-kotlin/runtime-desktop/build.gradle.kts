plugins {
  id("skillbill.kmp-application")
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:common"))
      implementation(project(":runtime-desktop:core:data"))
      implementation(project(":runtime-desktop:core:designsystem"))
      implementation(project(":runtime-desktop:core:domain"))
      implementation(project(":runtime-desktop:core:ui"))
      implementation(project(":runtime-desktop:feature:workbench"))
    }

    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
    }

    jvmTest.dependencies {
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}

compose.desktop {
  application {
    mainClass = "skillbill.desktop.MainKt"
  }
}
