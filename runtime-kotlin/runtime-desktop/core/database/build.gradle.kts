plugins {
  id("skillbill.kmp-library")
  alias(libs.plugins.room3)
}

room3 {
  schemaDirectory("$projectDir/schemas")
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
  }

  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:common"))
      implementation(libs.room3.runtime)
      implementation(libs.sqlite)
    }

    jvmMain.dependencies {
      implementation(libs.sqlite.jdbc)
    }

    jvmTest.dependencies {
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
    }
  }
}

dependencies {
  add("kspJvm", libs.room3.compiler)
}
