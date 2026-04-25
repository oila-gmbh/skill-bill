plugins {
  alias(libs.plugins.ksp)
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-core"))
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.compiler)
  testImplementation(project(":runtime-cli"))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
