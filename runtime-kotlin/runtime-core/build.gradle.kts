plugins {
  alias(libs.plugins.ksp)
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-application"))
  api(project(":runtime-contracts"))
  api(project(":runtime-domain"))
  api(project(":runtime-infra-fs"))
  api(project(":runtime-infra-http"))
  api(project(":runtime-infra-sqlite"))
  api(project(":runtime-ports"))
  implementation(libs.snakeyaml)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.compiler)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
