plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-contracts"))
  api(project(":runtime-domain"))
  api(project(":runtime-ports"))
  implementation(libs.kotlin.inject.runtime)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
