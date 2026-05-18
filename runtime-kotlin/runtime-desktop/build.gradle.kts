import org.gradle.api.DefaultTask
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path

plugins {
  id("skillbill.kmp-application")
}

abstract class FixRuntimeScriptPermissionsTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val appImageRoot: DirectoryProperty

  @TaskAction
  fun fixPermissions() {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
      return
    }

    val root = appImageRoot.get().asFile.toPath()
    val launchScripts = runtimeLaunchScriptsUnder(root)
    if (launchScripts.isEmpty()) {
      throw GradleException(
        "No bundled runtime launch scripts were found under ${root.toAbsolutePath()}.",
      )
    }
    launchScripts.forEach { script ->
      if (!script.toFile().setExecutable(true, false)) {
        throw GradleException(
          "Failed to mark bundled runtime launch script executable: ${script.toAbsolutePath()}",
        )
      }
    }
  }

  private fun runtimeLaunchScriptsUnder(root: Path): List<Path> =
    if (!Files.exists(root)) {
      emptyList()
    } else {
      Files.walk(root).use { paths ->
        paths
          .filter(::isRuntimeLaunchScript)
          .toList()
      }
    }

  private fun isRuntimeLaunchScript(path: Path): Boolean {
    val binDir = path.parent ?: return false
    val runtimeDir = binDir.parent ?: return false
    return Files.isRegularFile(path) &&
      binDir.fileName.toString() == "bin" &&
      runtimeDir.fileName.toString() in setOf("runtime-cli", "runtime-mcp") &&
      !path.fileName.toString().endsWith(".bat")
  }
}

val repoRoot = rootProject.projectDir.parentFile
val desktopAppResourcesDir = layout.buildDirectory.dir("generated/desktop-app-resources")
val runtimeResourceDirName = "skill-bill-runtime"
val runtimeCliInstallDir =
  rootProject.layout.projectDirectory.dir(
    "runtime-cli/build/install/runtime-cli",
  )
val runtimeMcpInstallDir =
  rootProject.layout.projectDirectory.dir(
    "runtime-mcp/build/install/runtime-mcp",
  )

fun CopySpec.excludeGeneratedSkillBillArtifacts() {
  exclude("**/SKILL.md")
  exclude("**/shell-ceremony.md")
  exclude("**/telemetry-contract.md")
  exclude("**/stack-routing.md")
  exclude("**/review-delegation.md")
  exclude("**/review-routing.md")
  exclude("**/quality-routing.md")
  exclude("**/claude-agents/**")
  exclude("**/codex-agents/**")
  exclude("**/opencode-agents/**")
  exclude("**/junie-agents/**")
}

val prepareDesktopRuntimeBundle by tasks.registering(Sync::class) {
  group = "distribution"
  description = "Stage authored Skill Bill runtime assets for desktop native packages."
  duplicatesStrategy = DuplicatesStrategy.FAIL
  dependsOn(":runtime-cli:installDist", ":runtime-mcp:installDist")

  into(desktopAppResourcesDir)

  from(runtimeCliInstallDir) {
    into("common/$runtimeResourceDirName/runtime-cli")
  }
  from(runtimeMcpInstallDir) {
    into("common/$runtimeResourceDirName/runtime-mcp")
  }
  from(repoRoot.resolve("skills")) {
    into("common/$runtimeResourceDirName/skills")
    excludeGeneratedSkillBillArtifacts()
  }
  from(repoRoot.resolve("platform-packs")) {
    into("common/$runtimeResourceDirName/platform-packs")
    excludeGeneratedSkillBillArtifacts()
  }
  from(repoRoot.resolve("orchestration")) {
    into("common/$runtimeResourceDirName/orchestration")
  }
}

val fixDesktopRuntimeScriptPermissions by tasks.registering(
  FixRuntimeScriptPermissionsTask::class,
) {
  group = "distribution"
  description = "Mark bundled Skill Bill runtime scripts executable in desktop app images."
  appImageRoot.set(layout.buildDirectory.dir("compose/binaries/main/app"))
  dependsOn("createDistributable")
}

tasks.register("prepareDesktopAppDistributable") {
  group = "distribution"
  description = "Create a desktop app image with bundled runtime scripts ready to execute."
  dependsOn(fixDesktopRuntimeScriptPermissions)
}

kotlin {
  sourceSets {
    commonMain.dependencies {
      implementation(project(":runtime-desktop:core:common"))
      implementation(project(":runtime-desktop:core:data"))
      implementation(project(":runtime-desktop:core:database"))
      implementation(project(":runtime-desktop:core:datastore"))
      implementation(project(":runtime-desktop:core:designsystem"))
      implementation(project(":runtime-desktop:core:domain"))
      implementation(project(":runtime-desktop:core:navigation"))
      implementation(project(":runtime-desktop:core:ui"))
      implementation(project(":runtime-desktop:feature:skillbill"))
    }

    jvmMain.dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.serialization.json)
    }

    jvmTest.dependencies {
      implementation(libs.junit.jupiter)
      implementation(libs.kotlin.test)
    }
  }
}

compose.desktop {
  application {
    mainClass = "skillbill.desktop.MainKt"

    nativeDistributions {
      appResourcesRootDir.set(desktopAppResourcesDir)
      targetFormats(
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
      )
      packageName = "SkillBill"
      packageVersion = "1.0.0"
      includeAllModules = true

      val iconsDir = layout.projectDirectory.dir("icons")
      macOS {
        iconFile.set(iconsDir.file("icon.icns").asFile)
      }
      windows {
        iconFile.set(iconsDir.file("icon.ico").asFile)
      }
      linux {
        iconFile.set(iconsDir.file("icon.png").asFile)
      }
    }
  }
}

tasks.matching { task ->
  task.name == "createDistributable" ||
    task.name == "prepareAppResources" ||
    task.name == "packageDistributionForCurrentOS" ||
    task.name.startsWith("packageDmg") ||
    task.name.startsWith("packageMsi") ||
    task.name.startsWith("packageDeb") ||
    task.name.startsWith("packageRpm")
}.configureEach {
  dependsOn(prepareDesktopRuntimeBundle)
}

tasks.matching { task ->
  task.name == "packageDistributionForCurrentOS" ||
    task.name.startsWith("packageDmg") ||
    task.name.startsWith("packageMsi") ||
    task.name.startsWith("packageDeb") ||
    task.name.startsWith("packageRpm")
}.configureEach {
  dependsOn(fixDesktopRuntimeScriptPermissions)
}
