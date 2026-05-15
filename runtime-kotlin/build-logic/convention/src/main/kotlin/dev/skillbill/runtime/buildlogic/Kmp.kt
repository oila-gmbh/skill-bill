package dev.skillbill.runtime.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private const val JDK_VERSION = 17

internal fun Project.configureKmpDesktop(extension: KotlinMultiplatformExtension) {
  extension.apply {
    jvmToolchain(JDK_VERSION)
    jvm()

    compilerOptions {
      freeCompilerArgs.addAll(
        "-Xjsr305=strict",
        "-opt-in=kotlin.RequiresOptIn",
        "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
      )
    }
  }

  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
      allWarningsAsErrors.set(true)
    }
  }

  tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
      events("passed", "skipped", "failed")
      exceptionFormat = TestExceptionFormat.FULL
    }
  }
}

internal fun Project.configureKmpComposeApplication(extension: KotlinMultiplatformExtension) {
  configureKmpDesktop(extension)
}
