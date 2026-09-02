package skillbill.architecture

import java.nio.file.Files
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

    val cycles = ArchitectureScanSupport.applicationPackageCycles()
    Files.writeString(
      baselineDir.resolve("application-package-cycle-baseline.txt"),
      cycles.map { cycle -> cycle.areas.sorted().joinToString("|") }.sorted().joinToString("\n") + "\n",
    )

    val clockSites = ArchitectureScanSupport.runtimeApplicationAmbientClockCallSites()
      .map { site -> "${site.relativePath}:${site.lineNumber}:${site.call}" }
    Files.writeString(
      baselineDir.resolve("runtime-application-ambient-clock-baseline.txt"),
      clockSites.joinToString("\n") + "\n",
    )

    Files.writeString(
      baselineDir.resolve("inject-constructor-defaults-baseline.txt"),
      "",
    )
  }
}
