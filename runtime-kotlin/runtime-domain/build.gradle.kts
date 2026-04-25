plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  implementation(project(":runtime-contracts"))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
