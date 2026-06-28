package dev.skillbill.runtime.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

val Project.libs: VersionCatalog
  get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun Project.composeResourcesDependency(): String =
  "org.jetbrains.compose.components:components-resources:${libs.findVersion("compose").get()}"
