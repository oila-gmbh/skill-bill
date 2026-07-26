import java.io.File
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

val canonicalSpecialistContractPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/review-orchestrator/specialist-contract.md")
    .absolutePath

val copySpecialistContract =
  tasks.register<Copy>("copySpecialistContract") {
    val contractPath = canonicalSpecialistContractPath
    from(contractPath)
    into(layout.buildDirectory.dir("generated/review-contract/skillbill/review"))
    inputs.file(contractPath)
    doFirst {
      require(File(contractPath).isFile) {
        "Authoritative delegated-review specialist contract is missing at $contractPath."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(copySpecialistContract)
}

dependencies {
  implementation(project(":runtime-contracts"))
  testImplementation(libs.jackson.databind)
  testImplementation(libs.jackson.dataformat.yaml)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}
