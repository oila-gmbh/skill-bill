import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `kotlin-dsl`
  alias(libs.plugins.spotless)
  alias(libs.plugins.detekt)
}

group = "dev.skillbill.runtime.buildlogic"

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  compileOnly(libs.kotlin.gradle.plugin)
  compileOnly(libs.spotless.gradle.plugin)
  compileOnly(libs.detekt.gradle.plugin)
  // SKILL-55 subtask 1 (F-004/F-005): the skillbill.runtime-image convention plugin
  // references org.beryx.runtime.* types (RuntimePluginExtension) AND applies the
  // `org.beryx.runtime` plugin to consumers. `implementation` (not compileOnly) puts the
  // Badass Runtime artifact on the plugin classpath of every project that applies the
  // convention plugin, so `pluginManager.apply("org.beryx.runtime")` resolves.
  implementation(libs.beryx.runtime.gradle.plugin)
  // SKILL-55 subtask 2 (task 6): unit-test the pure DesktopVersions helper. build-logic is
  // a SEPARATE included build with its own dependency declarations; it reuses the shared
  // version catalog (settings.gradle.kts maps `../gradle/libs.versions.toml`), so the same
  // junit-jupiter alias runtime-desktop's jvmTest uses resolves here too. The pure helper
  // under test references no Gradle types, so plain JUnit5 (no kotlin-test junit5 binding)
  // is sufficient and keeps the included-build test classpath minimal.
  testImplementation(libs.junit.jupiter)
  // Gradle's test worker needs the JUnit Platform launcher on the test runtime classpath.
  // In application/library projects the Kotlin/Java plugins add it implicitly; this
  // included build needs it declared explicitly so `useJUnitPlatform()` can start.
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}

// build-logic is a separate `includeBuild`. Compiling its test sources with Kotlin
// 2.4.0-Beta2 under Gradle 9.3 trips a known Kotlin-Gradle-plugin config-cache bug: the
// test-compile path stores the Kotlin daemon error-file list / profile File where Gradle
// expects a Property/ListProperty, which the project-wide configuration cache rejects.
// Main-source compilation is unaffected. Opt the test-compile/test tasks out of the
// configuration cache via the SUPPORTED Gradle API (the same mechanism subtask 1 uses for
// the Badass Runtime tasks) so the global config cache stays warm for everything else
// while these tasks still run correctly. This is not a lint suppression.
val ccOptOutReason =
  "Kotlin 2.4.0-Beta2 test-compile is not configuration-cache compatible under Gradle 9.3 " +
    "(serializes the Kotlin daemon error-file/profile as a File where a Property is expected)."
tasks.named("compileTestKotlin") { notCompatibleWithConfigurationCache(ccOptOutReason) }
tasks.withType<Test>().configureEach { notCompatibleWithConfigurationCache(ccOptOutReason) }

spotless {
  kotlin {
    target("src/**/*.kt")
    ktlint().editorConfigOverride(
      mapOf(
        "indent_size" to 2,
        "ij_kotlin_allow_trailing_comma" to true,
        "ij_kotlin_allow_trailing_comma_on_call_site" to true,
        "max_line_length" to 120,
      ),
    )
    trimTrailingWhitespace()
    endWithNewline()
  }

  kotlinGradle {
    target("*.gradle.kts")
    ktlint()
    trimTrailingWhitespace()
    endWithNewline()
  }
}

detekt {
  config.setFrom(file("$rootDir/../config/detekt/detekt.yml"))
  buildUponDefaultConfig = true
  parallel = true
  basePath = rootDir.absolutePath
}

tasks.named("check") {
  dependsOn("spotlessCheck")
}

tasks {
  validatePlugins {
    enableStricterValidation = true
    failOnWarning = true
  }
}

gradlePlugin {
  plugins {
    register("jvmLibrary") {
      id = "skillbill.jvm-library"
      implementationClass = "JvmLibraryConventionPlugin"
    }
    register("kmpComposeApplication") {
      id = "skillbill.kmp-compose-application"
      implementationClass = "KmpComposeApplicationConventionPlugin"
    }
    register("kmpLibrary") {
      id = "skillbill.kmp-library"
      implementationClass = "KmpLibraryConventionPlugin"
    }
    register("kmpCompose") {
      id = "skillbill.kmp-compose"
      implementationClass = "KmpComposeConventionPlugin"
    }
    register("kmpApplication") {
      id = "skillbill.kmp-application"
      implementationClass = "KmpApplicationConventionPlugin"
    }
    register("kmpKotlinInject") {
      id = "skillbill.kmp-kotlininject"
      implementationClass = "KmpKotlinInjectConventionPlugin"
    }
    register("quality") {
      id = "skillbill.quality"
      implementationClass = "QualityConventionPlugin"
    }
    register("runtimeImage") {
      id = "skillbill.runtime-image"
      implementationClass = "RuntimeImageConventionPlugin"
    }
  }
}
