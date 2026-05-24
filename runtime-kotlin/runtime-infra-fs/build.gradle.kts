import java.io.File

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-ports"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-domain"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.snakeyaml)
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

val canonicalPlatformPackSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/platform-pack-schema.yaml")
    .absolutePath

val copyPlatformPackSchema =
  tasks.register<Copy>("copyPlatformPackSchema") {
    val schemaPath = canonicalPlatformPackSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-52: canonical platform-pack schema is missing at $schemaPath. " +
          "Run from the repo root and ensure " +
          "`orchestration/contracts/platform-pack-schema.yaml` exists."
      }
    }
  }

val canonicalNativeAgentCompositionSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/native-agent-composition-schema.yaml")
    .absolutePath

val copyNativeAgentCompositionSchema =
  tasks.register<Copy>("copyNativeAgentCompositionSchema") {
    val schemaPath = canonicalNativeAgentCompositionSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).exists()) {
        "SKILL-52: canonical native-agent composition schema is missing at $schemaPath. " +
          "Run from the repo root and ensure the schema file exists."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.named("processResources") {
  dependsOn(copyPlatformPackSchema)
  dependsOn(copyNativeAgentCompositionSchema)
}

tasks.named("processTestResources") {
  dependsOn(copyPlatformPackSchema)
  dependsOn(copyNativeAgentCompositionSchema)
}
