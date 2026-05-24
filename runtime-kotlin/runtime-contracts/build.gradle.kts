import org.gradle.api.tasks.testing.Test
import java.io.File

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(libs.kotlinx.serialization.json)
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

val canonicalWorkflowStateSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/workflow-state-schema.yaml")
    .absolutePath

val copyWorkflowStateSchema =
  tasks.register<Copy>("copyWorkflowStateSchema") {
    val schemaPath = canonicalWorkflowStateSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-52: canonical workflow-state schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

val canonicalInstallPlanSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/install-plan-schema.yaml")
    .absolutePath

val copyInstallPlanSchema =
  tasks.register<Copy>("copyInstallPlanSchema") {
    val schemaPath = canonicalInstallPlanSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-52: canonical install-plan schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

val canonicalDecompositionManifestSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/decomposition-manifest-schema.yaml")
    .absolutePath

val copyDecompositionManifestSchema =
  tasks.register<Copy>("copyDecompositionManifestSchema") {
    val schemaPath = canonicalDecompositionManifestSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-52: canonical decomposition manifest schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.named("processResources") {
  dependsOn(copyWorkflowStateSchema)
  dependsOn(copyInstallPlanSchema)
  dependsOn(copyDecompositionManifestSchema)
}

tasks.named("processTestResources") {
  dependsOn(copyWorkflowStateSchema)
  dependsOn(copyInstallPlanSchema)
  dependsOn(copyDecompositionManifestSchema)
}

tasks.withType<Test>().configureEach {
  if (project.hasProperty("update-snapshots")) {
    systemProperty("update-snapshots", "true")
  }
}
