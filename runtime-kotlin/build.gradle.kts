plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.ksp)
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.detekt) apply false
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

group = "dev.skillbill"
version = "0.1.0-SNAPSHOT"

val buildLogicCheck = gradle.includedBuild("build-logic").task(":convention:check")
val buildLogicDetekt = gradle.includedBuild("build-logic").task(":convention:detekt")
val buildLogicSpotlessCheck = gradle.includedBuild("build-logic").task(":convention:spotlessCheck")

dependencies {
  implementation(libs.clikt)
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.sqlite.jdbc)
  implementation(libs.kotlinx.serialization.json)
  ksp(libs.kotlin.inject.compiler)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

tasks.named("check") {
  dependsOn(buildLogicCheck)
}

tasks.named("detekt") {
  dependsOn(buildLogicDetekt)
}

tasks.named("spotlessCheck") {
  dependsOn(buildLogicSpotlessCheck)
}
