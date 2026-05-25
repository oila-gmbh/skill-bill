plugins {
  alias(libs.plugins.ksp)
  application
  id("skillbill.jvm-library")
  id("skillbill.quality")
}

dependencies {
  // SKILL-52.2 subtask 5: narrowed allow-list pinned by
  // `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeAdapterDependencyAllowlistTest.kt`.
  // runtime-infra-fs / runtime-infra-http were dropped — runtime-cli has no
  // concrete `skillbill.infrastructure.*` imports outside test sources; the
  // infrastructure adapters are resolved through `RuntimeComponent` (kotlin-inject).
  implementation(project(":runtime-application"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-core"))
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-ports"))
  implementation(libs.clikt)
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.kotlinx.serialization.json)
  ksp(libs.kotlin.inject.compiler)
  // SKILL-52.2 subtask 5: runtime-infra-fs / runtime-infra-http stay on the
  // test classpath because `RuntimeModuleSmokeTest` and adapter-side smoke tests
  // import concrete runtime classes (`InstallRuntime`, `LauncherRuntime`,
  // `NativeAgentRuntime`, `ScaffoldRuntime`, telemetry HTTP runtime). Test code
  // crossing module boundaries for fixtures is expected; main source must not.
  testImplementation(project(":runtime-infra-fs"))
  testImplementation(project(":runtime-infra-http"))
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
