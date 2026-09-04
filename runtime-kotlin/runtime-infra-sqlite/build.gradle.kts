plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-ports"))
  implementation(project(":runtime-contracts"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.sqlite.jdbc)
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
