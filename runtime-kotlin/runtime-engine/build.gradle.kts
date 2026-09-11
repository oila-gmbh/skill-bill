plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
  `java-test-fixtures`
}

dependencies {
  api(project(":runtime-contracts"))
  api(project(":runtime-domain"))
  api(project(":runtime-ports"))
  api(project(":runtime-application"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.kotlinx.serialization.json)
  testFixturesImplementation(project(":runtime-domain"))
  testFixturesImplementation(project(":runtime-ports"))
  testFixturesImplementation(project(":runtime-infra-sqlite"))
  testFixturesImplementation(testFixtures(project(":runtime-application")))
  testFixturesImplementation(testFixtures(project(":runtime-ports")))
  testFixturesImplementation(testFixtures(project(":runtime-domain")))
  testImplementation(testFixtures(project(":runtime-ports")))
  testImplementation(testFixtures(project(":runtime-domain")))
  testImplementation(testFixtures(project(":runtime-application")))
  testImplementation(project(":runtime-infra-fs"))
  testImplementation(project(":runtime-infra-sqlite"))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.jackson.dataformat.yaml)
}
