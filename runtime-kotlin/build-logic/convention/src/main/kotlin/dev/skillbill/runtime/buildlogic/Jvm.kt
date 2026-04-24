package dev.skillbill.runtime.buildlogic

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private const val JDK_VERSION = 17

internal fun Project.configureKotlinJvm() {
  extensions.configure(KotlinJvmProjectExtension::class.java) {
    jvmToolchain(JDK_VERSION)
  }

  extensions.configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(JDK_VERSION))
    }
    withSourcesJar()
  }

  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_17)
      allWarningsAsErrors.set(true)
      freeCompilerArgs.add("-Xjsr305=strict")
    }
  }

  tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
    useJUnitPlatform()
    testLogging {
      events("passed", "skipped", "failed")
      exceptionFormat = TestExceptionFormat.FULL
    }
  }
}
