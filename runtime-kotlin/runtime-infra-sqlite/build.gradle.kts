import java.io.File
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-domain"))
  api(project(":runtime-ports"))
  implementation(project(":runtime-contracts"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.sqlite.jdbc)
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

val canonicalReviewLifecycleSchemaPath: String =
  rootProject.projectDir.parentFile
    .resolve("orchestration/contracts/review-lifecycle-schema.yaml")
    .absolutePath

val copyReviewLifecycleSchema =
  tasks.register<Copy>("copyReviewLifecycleSchema") {
    val schemaPath = canonicalReviewLifecycleSchemaPath
    from(schemaPath)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
    inputs.file(schemaPath)
    doFirst {
      require(File(schemaPath).isFile) {
        "SKILL-145: canonical delegated review lifecycle schema is missing at $schemaPath."
      }
    }
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.withType<ProcessResources>().configureEach {
  dependsOn(copyReviewLifecycleSchema)
}
