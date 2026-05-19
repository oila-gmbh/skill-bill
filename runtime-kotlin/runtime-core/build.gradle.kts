import org.gradle.api.tasks.testing.Test

plugins {
  alias(libs.plugins.ksp)
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  api(project(":runtime-application"))
  api(project(":runtime-contracts"))
  api(project(":runtime-domain"))
  api(project(":runtime-infra-fs"))
  api(project(":runtime-infra-http"))
  api(project(":runtime-infra-sqlite"))
  api(project(":runtime-ports"))
  implementation(libs.snakeyaml)
  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.compiler)
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

// SKILL-47: copy the canonical platform-pack JSON Schema into the runtime-core
// resources at build time. The on-disk file under `orchestration/contracts/`
// remains the source of truth for the desktop schema viewer; this task pulls
// the same bytes into the JVM classpath so the runtime validator can locate
// the schema without depending on the runtime working directory matching the
// repo root.
//
// F-008: these paths MUST mirror `PlatformPackSchemaPaths.REPO_RELATIVE_PATH`
// and `PlatformPackSchemaPaths.CLASSPATH_RESOURCE` in
// `runtime-core/src/main/kotlin/skillbill/scaffold/PlatformPackSchemaValidator.kt`.
// Gradle's Kotlin DSL cannot import runtime constants; if these strings drift
// the runtime loader will loud-fail because the classpath resource will be
// absent at the expected location.
val copyPlatformPackSchema =
  tasks.register<Copy>("copyPlatformPackSchema") {
    val canonicalSchema =
      rootProject.projectDir.parentFile
        .resolve("orchestration/contracts/platform-pack-schema.yaml")
    from(canonicalSchema)
    into(layout.buildDirectory.dir("generated/skillbill-contracts/skillbill/contracts"))
  }

sourceSets.named("main") {
  resources.srcDir(layout.buildDirectory.dir("generated/skillbill-contracts"))
}

tasks.named("processResources") {
  dependsOn(copyPlatformPackSchema)
}

tasks.withType<Test>().configureEach {
  if (project.hasProperty("update-snapshots")) {
    systemProperty("update-snapshots", "true")
  }
}
