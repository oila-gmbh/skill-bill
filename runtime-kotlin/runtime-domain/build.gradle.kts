import org.gradle.language.jvm.tasks.ProcessResources

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

tasks.named<ProcessResources>("processResources") {
  val skillBillVersion = project.version.toString()
  inputs.property("skillBillVersion", skillBillVersion)
  filesMatching("skillbill/version.properties") {
    expand("skillBillVersion" to skillBillVersion)
  }
}

dependencies {
  implementation(project(":runtime-contracts"))
  testImplementation(libs.jackson.databind)
  testImplementation(libs.jackson.dataformat.yaml)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
