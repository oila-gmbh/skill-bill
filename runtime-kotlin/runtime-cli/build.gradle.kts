plugins {
  alias(libs.plugins.ksp)
  application
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  implementation(project(":runtime-application"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-core"))
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-infra-fs"))
  implementation(project(":runtime-infra-http"))
  implementation(project(":runtime-ports"))
  implementation(libs.clikt)
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.kotlinx.serialization.json)
  ksp(libs.kotlin.inject.compiler)
  testImplementation(project(":runtime-infra-sqlite"))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

application {
  mainClass.set("skillbill.cli.MainKt")
}

tasks.named<JavaExec>("run") {
  standardInput = System.`in`
}

val validateAgentConfigs by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Validate repository agent configuration and governed generated-output drift."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set(application.mainClass)
  args("validate-agent-configs", "--repo-root", rootProject.projectDir.parentFile.absolutePath)
  mustRunAfter(tasks.withType<Test>())
}

tasks.named("check") {
  dependsOn(validateAgentConfigs)
}
