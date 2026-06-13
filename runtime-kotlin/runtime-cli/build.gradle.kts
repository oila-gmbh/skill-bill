plugins {
  alias(libs.plugins.ksp)
  application
  id("skillbill.jvm-library")
  id("skillbill.quality")
  // SKILL-55 subtask 1 (F-004/F-005): self-contained jlink image wiring is hoisted into
  // the skillbill.runtime-image convention plugin. It applies org.beryx.runtime, the
  // shared module set (incl. java.net.http), the lazy Java 17 link toolchain, the
  // versioned imageZip name, the sha256 sidecar, and the CC opt-out. We only declare the
  // varying input below: the launcher / archive base name.
  id("skillbill.runtime-image")
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
  mainClass.set("skillbill.cli.core.MainKt")
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

// SKILL-55 subtask 1: self-contained runtime image via the skillbill.runtime-image
// convention plugin (Badass Runtime / jlink). The Kotlin app is non-modular; the plugin
// wraps the existing `application` installDist distribution, so the image keeps the
// `bin/runtime-cli` launcher (= applicationName) that subtask 4 will symlink to
// `skill-bill` (AC3). `application` + installDist stay intact; desktop bundling untouched.
runtimeImage {
  imageBaseName.set("runtime-cli")
}
