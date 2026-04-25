plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-ports"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-domain"))
  implementation(libs.kotlin.inject.runtime)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
