plugins {
  alias(libs.plugins.ksp)
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.sqlite.jdbc)
  api(libs.kotlinx.serialization.json)
  ksp(libs.kotlin.inject.compiler)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
