package dev.skillbill.runtime.buildlogic

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

private const val JDK_VERSION = 21
private const val MAX_TEST_FORKS = 8
private const val TEST_FORK_CPU_DIVISOR = 2
private const val TEST_MAX_HEAP = "2g"

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

  tasks.withType(Test::class.java).configureEach {
    useJUnitPlatform()
    maxParallelForks =
      (Runtime.getRuntime().availableProcessors() / TEST_FORK_CPU_DIVISOR).coerceIn(1, MAX_TEST_FORKS)
    maxHeapSize = TEST_MAX_HEAP
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
    if (project.hasProperty("update-snapshots")) {
      systemProperty("update-snapshots", "true")
    }
    testLogging {
      events("skipped", "failed")
      exceptionFormat = TestExceptionFormat.FULL
      quiet {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
      }
      error {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
      }
    }
  }

  configureRepoTestSourceSet()
}

private fun Project.configureRepoTestSourceSet() {
  val repoTestRoot = layout.projectDirectory.dir("src/repoTest/kotlin").asFile
  if (!repoTestRoot.isDirectory) {
    return
  }

  val java = extensions.getByType(JavaPluginExtension::class.java)
  val repoTestSourceSet = java.sourceSets.create("repoTest")
  val testSourceSet = java.sourceSets.getByName("test")

  configurations.getByName(repoTestSourceSet.implementationConfigurationName)
    .extendsFrom(configurations.getByName(testSourceSet.implementationConfigurationName))
  configurations.getByName(repoTestSourceSet.runtimeOnlyConfigurationName)
    .extendsFrom(configurations.getByName(testSourceSet.runtimeOnlyConfigurationName))

  extensions.configure(KotlinJvmProjectExtension::class.java) {
    val compilations = target.compilations
    compilations.getByName("repoTest").associateWith(compilations.getByName("test"))
  }

  dependencies.add(repoTestSourceSet.implementationConfigurationName, testSourceSet.output)

  val repoTest = tasks.register("repoTest", Test::class.java) {
    group = "verification"
    description = "Repository-contract suites that read governed sources outside runtime-kotlin."
    testClassesDirs = repoTestSourceSet.output.classesDirs
    classpath = repoTestSourceSet.runtimeClasspath
    inputs.files(governedRepositorySources())
      .withPathSensitivity(PathSensitivity.RELATIVE)
      .withPropertyName("governedRepositorySources")
  }

  tasks.named("check") {
    dependsOn(repoTest)
  }
}

private fun Project.governedRepositorySources(): FileCollection {
  val repoRoot = rootProject.layout.projectDirectory.dir("..")
  return files(
    repoRoot.dir("skills"),
    repoRoot.dir("platform-packs"),
    repoRoot.dir("orchestration"),
    repoRoot.dir("agent-addons"),
    repoRoot.dir("docs"),
    repoRoot.dir("tests"),
    repoRoot.dir("scripts"),
    repoRoot.file("README.md"),
    repoRoot.file("LICENSE"),
    repoRoot.file("install.sh"),
    repoRoot.file("uninstall.sh"),
  )
}
