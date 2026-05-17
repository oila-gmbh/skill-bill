import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync

plugins {
  id("skillbill.kmp-application")
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

  into(desktopAppResourcesDir.map { dir -> dir.dir(runtimeResourceDirName).asFile })

  from(runtimeCliInstallDir) {
    into("runtime-cli")
  }
  from(runtimeMcpInstallDir) {
    into("runtime-mcp")
  }
  from(repoRoot.resolve("skills")) {
    into("skills")
    excludeGeneratedSkillBillArtifacts()
  }
  from(repoRoot.resolve("platform-packs")) {
    into("platform-packs")
    excludeGeneratedSkillBillArtifacts()
  }
  from(repoRoot.resolve("orchestration")) {
    into("orchestration")
  }
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
