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

private const val JDK_VERSION = 21

// Gates for the opt-in real-store harnesses. Test workers inherit the long-lived daemon's
// environment rather than the invoking shell's, so an exported gate never reaches the test JVM and
// the harness silently no-ops. These are forwarded explicitly through the provider API, which reads
// the client environment and re-runs the task when the value changes.
private val HARNESS_ENVIRONMENT_GATES = listOf(
  "SKILL_BILL_REAL_STORE_DB",
  "SKILL_BILL_MIGRATION_FIXTURE_DB",
)

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
      jvmTarget.set(JvmTarget.JVM_21)
      allWarningsAsErrors.set(true)
      freeCompilerArgs.add("-Xjsr305=strict")
    }
  }

  tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
    useJUnitPlatform()
    // The Claude Code harness exports CLAUDE_CONFIG_DIR; left inherited it leaks a real ~/.claude-*
    // profile root into install/apply discovery tests and breaks their exact-root assertions. Drop it
    // so the test JVM never depends on the running developer's ambient Claude profile.
    environment.remove("CLAUDE_CONFIG_DIR")
    HARNESS_ENVIRONMENT_GATES.forEach { gate ->
      val value = providers.environmentVariable(gate)
      inputs.property(gate, value).optional(true)
      if (value.isPresent) {
        environment(gate, value.get())
      }
    }
    testLogging {
      events("passed", "skipped", "failed")
      exceptionFormat = TestExceptionFormat.FULL
    }
  }
}
