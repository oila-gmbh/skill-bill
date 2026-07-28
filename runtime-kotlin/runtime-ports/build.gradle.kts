plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
  // SKILL-132 subtask 1: the empty port implementations are test-only fakes consumed by
  // runtime-core and runtime-application tests, so they ship as published test fixtures
  // instead of production declarations.
  `java-test-fixtures`
}

dependencies {
  api(project(":runtime-contracts"))
  api(project(":runtime-domain"))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
