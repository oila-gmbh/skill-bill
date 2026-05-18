package skillbill.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains

class DesktopPackagingConfigurationTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `native distributions cover mac windows and deb plus rpm linux packages`() {
    val buildFile = desktopBuildFile()

    assertContains(buildFile, "TargetFormat.Dmg")
    assertContains(buildFile, "TargetFormat.Msi")
    assertContains(buildFile, "TargetFormat.Deb")
    assertContains(buildFile, "TargetFormat.Rpm")
  }

  @Test
  fun `desktop packages stage runtime assets as app resources`() {
    val buildFile = desktopBuildFile()

    assertContains(buildFile, "prepareDesktopRuntimeBundle")
    assertContains(buildFile, "appResourcesRootDir.set(desktopAppResourcesDir)")
    assertContains(buildFile, "runtimeResourceDirName = \"skill-bill-runtime\"")
    assertContains(buildFile, "dependsOn(\":runtime-cli:installDist\", \":runtime-mcp:installDist\")")
    assertContains(buildFile, "into(\"common/\$runtimeResourceDirName/runtime-cli\")")
    assertContains(buildFile, "into(\"common/\$runtimeResourceDirName/runtime-mcp\")")
    assertContains(buildFile, "into(\"common/\$runtimeResourceDirName/skills\")")
    assertContains(buildFile, "into(\"common/\$runtimeResourceDirName/platform-packs\")")
    assertContains(buildFile, "into(\"common/\$runtimeResourceDirName/orchestration\")")
    assertContains(buildFile, "repoRoot.resolve(\"skills\")")
    assertContains(buildFile, "repoRoot.resolve(\"platform-packs\")")
    assertContains(buildFile, "repoRoot.resolve(\"orchestration\")")
    assertContains(buildFile, "exclude(\"**/SKILL.md\")")
    assertContains(buildFile, "exclude(\"**/codex-agents/**\")")
  }

  @Test
  fun `package tasks depend on runtime bundle staging`() {
    val buildFile = desktopBuildFile()

    assertContains(buildFile, "task.name == \"packageDistributionForCurrentOS\"")
    assertContains(buildFile, "task.name == \"prepareAppResources\"")
    assertContains(buildFile, "task.name.startsWith(\"packageRpm\")")
    assertContains(buildFile, "dependsOn(prepareDesktopRuntimeBundle)")
  }

  @Test
  fun `desktop app images keep bundled runtime launch scripts executable`() {
    val buildFile = desktopBuildFile()

    assertContains(buildFile, "runtimeLaunchScriptsUnder")
    assertContains(buildFile, "fixDesktopRuntimeScriptPermissions")
    assertContains(buildFile, "script.toFile().setExecutable(true, false)")
    assertContains(buildFile, "tasks.register(\"prepareDesktopAppDistributable\")")
    assertContains(buildFile, "dependsOn(fixDesktopRuntimeScriptPermissions)")
  }

  private fun desktopBuildFile(): String = Files.readString(runtimeRoot.resolve("runtime-desktop/build.gradle.kts"))
}
