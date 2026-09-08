plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-ports"))
  implementation(project(":runtime-contracts"))
  implementation(libs.kotlin.inject.runtime)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
