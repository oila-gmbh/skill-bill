plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
  // SKILL-129 subtask 5: `ReviewRecordingHarness` binds the production evidence broker and runner,
  // so runtime-core's durable/telemetry redaction proof consumes it as a published test fixture
  // instead of re-declaring a second, weaker harness.
  `java-test-fixtures`
}

dependencies {
  api(project(":runtime-contracts"))
  api(project(":runtime-domain"))
  api(project(":runtime-ports"))
  implementation(libs.kotlin.inject.runtime)
  // The harness must enforce budgets through the real FileSystemReviewEvidenceBroker; main source
  // still depends on the port only.
  testFixturesImplementation(project(":runtime-infra-fs"))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.jackson.dataformat.yaml)
}
