plugins {
  base
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.ksp) apply false
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.detekt) apply false
}

group = "dev.skillbill"

// Release builds set RELEASE_VERSION to the tag (e.g. v0.4.1). Local/dev builds
// derive the version from the latest reachable git tag, bump the patch, and add
// a -SNAPSHOT suffix (v0.4.1 -> 0.4.2-SNAPSHOT), so a from-source install reports
// the current development line instead of a frozen, hand-maintained string. The
// "0.0.0-SNAPSHOT" fallback only applies when neither a RELEASE_VERSION nor a
// reachable git tag is available (e.g. a tarball with no .git).
version = providers.environmentVariable("RELEASE_VERSION").orNull
  ?.takeIf(String::isNotBlank)
  ?: gitDevSnapshotVersion()
  ?: "0.0.0-SNAPSHOT"

fun gitDevSnapshotVersion(): String? {
  val describe = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
    isIgnoreExitValue = true
  }
  val tag = try {
    if (describe.result.get().exitValue != 0) return null
    describe.standardOutput.asText.get().trim()
  } catch (_: Exception) {
    return null
  }
  val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").matchEntire(tag) ?: return null
  val (major, minor, patch) = match.destructured
  return "$major.$minor.${patch.toInt() + 1}-SNAPSHOT"
}

subprojects {
  group = rootProject.group
  version = rootProject.version
}

val buildLogic = gradle.includedBuild("build-logic")
val buildLogicCheck = buildLogic.task(":convention:check")
val buildLogicDetekt = buildLogic.task(":convention:detekt")
val buildLogicSpotlessCheck = buildLogic.task(":convention:spotlessCheck")

tasks.named("check") {
  dependsOn(buildLogicCheck)
  dependsOn(subprojects.map { subproject -> subproject.tasks.named("check") })
}

tasks.register("detekt") {
  dependsOn(buildLogicDetekt)
  dependsOn(subprojects.mapNotNull { subproject -> subproject.tasks.findByName("detekt") })
}

tasks.register("spotlessCheck") {
  dependsOn(buildLogicSpotlessCheck)
  dependsOn(subprojects.map { subproject -> subproject.tasks.named("spotlessCheck") })
}
