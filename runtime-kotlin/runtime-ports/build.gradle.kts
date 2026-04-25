plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-domain"))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
