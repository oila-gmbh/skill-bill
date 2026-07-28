import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

val convergenceStateSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/feature-task-runtime-convergence-state-schema.yaml")
    .absolutePath

tasks.named<ProcessResources>("processResources") {
  val schemaPath = convergenceStateSchemaPath
  inputs.file(schemaPath)
  doFirst {
    require(File(schemaPath).isFile) {
      "Missing feature-task-runtime convergence-state contract: $schemaPath"
    }
  }
  from(schemaPath) {
    into("contracts")
  }
}

tasks.withType<Test>().configureEach {
  if (project.hasProperty("update-snapshots")) {
    systemProperty("update-snapshots", "true")
  }
}
