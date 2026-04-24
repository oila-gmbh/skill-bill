package dev.skillbill.runtime.buildlogic

import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

private const val MAX_LINE_LENGTH = 120

internal fun Project.configureQuality() {
  configure<SpotlessExtension> {
    kotlin {
      target("src/**/*.kt")
      ktlint().editorConfigOverride(
        mapOf(
          "indent_size" to 2,
          "ij_kotlin_allow_trailing_comma" to true,
          "ij_kotlin_allow_trailing_comma_on_call_site" to true,
          "max_line_length" to MAX_LINE_LENGTH,
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

  configure<DetektExtension> {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    basePath = rootDir.absolutePath
  }

  tasks.withType<Detekt>().configureEach {
    exclude("**/build/**", "**/generated/**")
    reports {
      html.required.set(true)
      xml.required.set(true)
    }
  }

  tasks.named("check") {
    dependsOn("spotlessCheck")
  }
}
