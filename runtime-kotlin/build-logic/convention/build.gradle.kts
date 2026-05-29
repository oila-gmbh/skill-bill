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
}

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
