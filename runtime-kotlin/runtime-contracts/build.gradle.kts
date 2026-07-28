import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

val convergenceStateSchema =
  rootProject.layout.projectDirectory.file(
    "orchestration/contracts/feature-task-runtime-convergence-state-schema.yaml",
  )

tasks.named<ProcessResources>("processResources") {
  inputs.file(convergenceStateSchema)
  doFirst {
    require(convergenceStateSchema.asFile.isFile) {
      "Missing feature-task-runtime convergence-state contract: ${convergenceStateSchema.asFile}"
    }
  }
  from(convergenceStateSchema) {
    into("contracts")
  }
}

tasks.withType<Test>().configureEach {
  if (project.hasProperty("update-snapshots")) {
    systemProperty("update-snapshots", "true")
  }
}
