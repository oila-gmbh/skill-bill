package dev.skillbill.runtime.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.kotlin.dsl.withType

private const val GUARD_RESOURCE = "/skill-bill-java-guard.sh"

private const val GUARD_ANCHOR = "# Determine the Java command to use to start the JVM."

private const val GUARD_MARKER = "skill_bill_required_java_major="

private const val EMBEDDED_JRE_MARKER = "JAVA_HOME=\"\$APP_HOME\""

internal fun Project.configureStartScriptJavaGuard() {
  val guard = object {}.javaClass.getResource(GUARD_RESOURCE)?.readText()
    ?: throw GradleException("Missing start-script Java guard resource $GUARD_RESOURCE")

  tasks.withType<CreateStartScripts>().configureEach {
    doLast {
      val script = unixScript
      val generated = script.readText()
      if (generated.contains(GUARD_MARKER)) return@doLast
      if (generated.contains(GUARD_ANCHOR)) {
        script.writeText(generated.replace(GUARD_ANCHOR, guard + GUARD_ANCHOR))
        return@doLast
      }
      if (generated.contains(EMBEDDED_JRE_MARKER)) return@doLast
      throw GradleException(
        "Start-script Java guard anchor is absent from ${script.name}. Gradle changed the " +
          "generated launcher; update GUARD_ANCHOR in StartScriptJavaGuard.kt.",
      )
    }
  }
}
