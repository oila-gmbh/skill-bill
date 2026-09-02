package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class ArchitectureBaselineRecorder {
  @Test
  fun recordBaselinesWhenRequested() {
    if (System.getenv("RECORD_ARCHITECTURE_BASELINES") != "1") return
    val baselineDir = ArchitectureScanSupport.runtimeRoot.resolve(
      "runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/baselines",
    )
    Files.createDirectories(baselineDir)

    val logicalCounts = ArchitectureScanSupport.logicalTypeLineCounts(
      productionRoots = listOf("runtime-kotlin", "intellij-plugin"),
    ).filter { (_, count) -> count > PrincipleEnforcementInventory.PRODUCTION_LINE_CEILING }
    Files.writeString(
      baselineDir.resolve("logical-type-line-ceiling-baseline.txt"),
      logicalCounts.entries.sortedBy { it.key }.joinToString("\n") { (fqn, count) -> "$fqn $count" } + "\n",
    )

    writePackageCycleBaseline(
      baselineDir.resolve("application-package-cycle-baseline.txt"),
      PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
      PrincipleEnforcementInventory.APPLICATION_PACKAGE_PREFIX,
    )
    writePackageCycleBaseline(
      baselineDir.resolve("runtime-cli-package-cycle-baseline.txt"),
      PrincipleEnforcementInventory.RUNTIME_CLI_MAIN,
      PrincipleEnforcementInventory.CLI_PACKAGE_PREFIX,
    )

    writeAmbientBaseline(
      baselineDir.resolve("runtime-application-ambient-clock-baseline.txt"),
      ArchitectureScanSupport.ambientClockCallSites(PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN),
    )
    writeAmbientBaseline(
      baselineDir.resolve("runtime-cli-ambient-clock-baseline.txt"),
      ArchitectureScanSupport.ambientClockCallSites(PrincipleEnforcementInventory.RUNTIME_CLI_MAIN),
    )
    writeAmbientBaseline(
      baselineDir.resolve("runtime-cli-ambient-environment-baseline.txt"),
      ArchitectureScanSupport.ambientEnvironmentCallSites(PrincipleEnforcementInventory.RUNTIME_CLI_MAIN),
    )

    writeInjectDefaultBaseline(
      baselineDir.resolve("runtime-cli-inject-constructor-defaults-baseline.txt"),
      PrincipleEnforcementInventory.RUNTIME_CLI_MAIN,
    )
  }

  private fun writePackageCycleBaseline(target: Path, scanRoot: String, packagePrefix: String) {
    val cycles = ArchitectureScanSupport.packageCycles(scanRoot, packagePrefix)
      .map { cycle -> cycle.areas.sorted().joinToString("|") }
      .sorted()
    Files.writeString(target, cycles.joinToString("\n") + "\n")
  }

  private fun writeAmbientBaseline(target: Path, sites: List<ArchitectureScanSupport.AmbientCallSite>) {
    Files.writeString(
      target,
      sites.joinToString("\n") { site -> ArchitectureScanSupport.encodeAmbientSite(site) } + "\n",
    )
  }

  private fun writeInjectDefaultBaseline(target: Path, scanRoot: String) {
    val sites = ArchitectureScanSupport.injectConstructorDefaultSites(scanRoot)
      .map { site -> "${site.relativePath}::${site.symbol}::${site.parameter}" }
    Files.writeString(target, sites.joinToString("\n") + "\n")
  }
}
