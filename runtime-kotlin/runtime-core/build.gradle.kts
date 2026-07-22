plugins {
  alias(libs.plugins.ksp)
  id("skillbill.jvm-library")
  id("skillbill.quality")
  // SKILL-48 C8: publish the shared `repoRootFromTest()` helper to downstream test code
  // (runtime-core's own tests and runtime-desktop:feature:skillbill jvmTest) via the
  // `java-test-fixtures` plugin so the four prior copies collapse into one source.
  `java-test-fixtures`
}

dependencies {
  api(project(":runtime-application"))
  api(project(":runtime-ports"))
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-infra-fs"))
  implementation(project(":runtime-infra-http"))
  implementation(project(":runtime-infra-sqlite"))
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.compiler)
  // SKILL-129 subtask 5: the durable/telemetry redaction proof drives the production review runner
  // through the shared recording harness rather than hand-building an accounting summary.
  testImplementation(testFixtures(project(":runtime-application")))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
