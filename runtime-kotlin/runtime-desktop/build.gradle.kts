import dev.skillbill.runtime.buildlogic.resolveHostRuntimeToken
import dev.skillbill.runtime.buildlogic.toJpackageVersion
import dev.skillbill.runtime.buildlogic.toMacAppVersion
import dev.skillbill.runtime.buildlogic.writeSha256Sidecar
import org.gradle.api.DefaultTask
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import java.io.File
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
val rootLicenseFile = repoRoot.resolve("LICENSE")
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
  from(rootLicenseFile) {
    into("common/$runtimeResourceDirName")
  }
}

val verifyDesktopLicense by tasks.registering {
  group = "verification"
  description = "Verify desktop app resources carry the root LICENSE unchanged."
  val rootLicensePath = rootLicenseFile.absolutePath
  val stagedLicensePath =
    layout.buildDirectory
      .file("generated/desktop-app-resources/common/$runtimeResourceDirName/LICENSE")
      .get()
      .asFile
      .absolutePath
  dependsOn(prepareDesktopRuntimeBundle)
  inputs.file(rootLicensePath)
  inputs.file(stagedLicensePath)
  doLast {
    val rootLicense = Path.of(rootLicensePath)
    val stagedLicense = Path.of(stagedLicensePath)
    if (!Files.isRegularFile(rootLicense) || !Files.isRegularFile(stagedLicense) ||
      Files.mismatch(rootLicense, stagedLicense) != -1L
    ) {
      throw GradleException("Desktop app resources do not contain the root LICENSE unchanged.")
    }
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

// SKILL-55 subtask 2 (AC2/AC6): rename each jpackage installer to the canonical
// SkillBill-<fullVersion>-<os>-<arch>.<ext> and write an adjacent <name>.sha256.
// fullVersion is the un-stripped project.version (e.g. 0.1.0-SNAPSHOT); the <os>-<arch>
// token is the SAME canonical contract subtask 1 exposes (resolveHostRuntimeToken). The
// Compose package tasks emit one installer into build/compose/binaries/main/<format>/;
// the produced filename embeds Compose's own version, so we MATCH BY EXTENSION within
// that dir. Mirrors RuntimeImageConventionPlugin — all paths resolve to primitive Strings
// OUTSIDE the doLast closure (never a File/Provider), the task opts out of the
// configuration cache, declares inputs/outputs, and depends on its package task. The
// SHA-256 sidecar is written via the shared writeSha256Sidecar helper (F-001) so the
// `<hex>  <name>\n` format stays byte-identical with the runtime-image zips.
val canonicalArtifactVersion = project.version.toString()

// F-001 parity: a null host token is a non-fatal known-gap at CONFIG time (lifecycle log),
// and a loud failure only when a rename task actually runs. Never error() at config time.
val hostRuntimeToken: String? = resolveHostRuntimeToken()
if (hostRuntimeToken == null) {
  logger.lifecycle(
    "SKILL-55: host os.name='${System.getProperty("os.name")}' " +
      "os.arch='${System.getProperty("os.arch")}' is not a canonical packaging target " +
      "(known gap). Canonical desktop-installer rename tasks will fail loudly if invoked " +
      "on this host; run them on a matching CI runner.",
  )
}

data class DesktopInstaller(val packageTask: String, val format: String, val ext: String)

val desktopInstallers =
  listOf(
    DesktopInstaller(packageTask = "packageDeb", format = "deb", ext = "deb"),
    DesktopInstaller(packageTask = "packageRpm", format = "rpm", ext = "rpm"),
    DesktopInstaller(packageTask = "packageDmg", format = "dmg", ext = "dmg"),
    DesktopInstaller(packageTask = "packageMsi", format = "msi", ext = "msi"),
  )

val renameTaskNames =
  desktopInstallers.map { installer ->
    val formatTitle = installer.format.replaceFirstChar { char -> char.uppercase() }
    val renameTaskName = "canonicalRename${formatTitle}Installer"
    // Resolve to primitive Strings OUTSIDE the doLast closure (F-101 parity).
    val installerDirPath =
      layout.buildDirectory
        .dir("compose/binaries/main/${installer.format}")
        .get()
        .asFile.absolutePath
    val canonicalName = "SkillBill-$canonicalArtifactVersion-$hostRuntimeToken.${installer.ext}"
    val extension = installer.ext
    val tokenIsResolved = hostRuntimeToken != null
    tasks.register(renameTaskName) {
      group = "distribution"
      description =
        "Rename the ${installer.format} installer to $canonicalName and write its " +
        ".sha256 (AC2/AC6)."
      dependsOn(installer.packageTask)
      inputs.dir(installerDirPath)
      outputs.file("$installerDirPath/$canonicalName")
      outputs.file("$installerDirPath/$canonicalName.sha256")
      notCompatibleWithConfigurationCache(
        "Renames the output of the not-cacheable Compose jpackage package task.",
      )
      doLast {
        if (!tokenIsResolved) {
          val osName = System.getProperty("os.name")
          val osArch = System.getProperty("os.arch")
          throw GradleException(
            "SKILL-55: cannot produce a canonical desktop installer name on this host " +
              "(os.name='$osName', os.arch='$osArch'). " +
              "This is a known-gap target; build on a matching CI runner.",
          )
        }
        val installerDir = File(installerDirPath)
        val produced =
          installerDir
            .listFiles { file ->
              file.isFile &&
                file.extension.equals(extension, ignoreCase = true) &&
                file.name != canonicalName
            }
            ?.toList()
            .orEmpty()
        if (produced.isEmpty()) {
          throw GradleException(
            "SKILL-55: expected exactly one .$extension installer under " +
              "${installerDir.absolutePath} produced by ${installer.packageTask}, found none.",
          )
        }
        if (produced.size > 1) {
          throw GradleException(
            "SKILL-55: expected exactly one .$extension installer under " +
              "${installerDir.absolutePath}, found ${produced.size}: " +
              produced.joinToString { it.name } + ". Clean the dir and rebuild.",
          )
        }
        val canonical = File(installerDir, canonicalName)
        produced.single().copyTo(canonical, overwrite = true)
        writeSha256Sidecar(canonical)
        logger.lifecycle(
          "SKILL-55: wrote $canonicalName (+ .sha256) under ${installerDir.absolutePath}",
        )
      }
    }
    renameTaskName
  }

// Convenience aggregate: rename + checksum every installer this host's package tasks
// produced. Locally only Deb/Rpm are exercised; Dmg/Msi rename tasks are wired the same
// way and run on subtask 3's macOS / Windows CI runners.
tasks.register("packageDesktopInstallers") {
  group = "distribution"
  description =
    "Build, canonically rename, and checksum all desktop installers for the current host " +
    "(SkillBill-<version>-<os>-<arch>.<ext> + .sha256). Locally Deb/Rpm; Dmg/Msi on CI."
  dependsOn(renameTaskNames)
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
      licenseFile.set(rootLicenseFile)
      targetFormats(
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
      )
      packageName = "SkillBill"
      // SKILL-55 subtask 2 (AC1/F-007): jpackage requires a strict numeric
      // MAJOR.MINOR.PATCH; derive it from project.version (`0.1.0-SNAPSHOT` -> `0.1.0`)
      // instead of hardcoding. The full project.version is still used for the canonical
      // artifact file name in the rename/sha256 task below.
      packageVersion = toJpackageVersion(project.version.toString())
      includeAllModules = true

      val iconsDir = layout.projectDirectory.dir("icons")
      macOS {
        iconFile.set(iconsDir.file("icon.icns").asFile)
        // macOS (and the Compose Dmg validator, eager at config time) require MAJOR > 0.
        // project.version is 0.1.0 (major 0) — legal for .deb/.rpm/.msi but not .dmg — so
        // the macOS package version bumps a zero major to 1 (0.1.0 -> 1.1.0). Scoped to
        // macOS only; Linux/Windows keep the honest toJpackageVersion above.
        packageVersion = toMacAppVersion(project.version.toString())
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
  dependsOn(verifyDesktopLicense)
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
