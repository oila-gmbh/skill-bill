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
  testImplementation(libs.jackson.dataformat.yaml)
  // Canonical-output equivalence has to run through the production schema validator, not a double:
  // the wrapper-form extraction under test lives there.
  testImplementation(project(":runtime-infra-fs"))
}
