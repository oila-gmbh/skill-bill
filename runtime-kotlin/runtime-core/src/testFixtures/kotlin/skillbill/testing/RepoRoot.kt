package skillbill.testing

import java.nio.file.Files
import java.nio.file.Path

/**
 * SKILL-48 C8: shared helper that locates the skill-bill repo root from inside a JVM test.
 *
 * Walks up from the JVM working directory (the Gradle test executor's CWD, which lives
 * somewhere under the Gradle module being tested) until it finds a directory that contains
 * both:
 *
 *  - `runtime-kotlin/settings.gradle.kts` (anchors the Gradle build root), and
 *  - `orchestration/contracts/` (anchors the canonical-contract directory the tests need).
 *
 * Returns the first such directory. Throws [IllegalStateException] when the walk reaches
 * the filesystem root without finding both markers.
 *
 * This helper consolidates four near-identical copies that previously lived inside the
 * runtime-core and runtime-desktop test sources (`PlatformPackSchemaContractVersionTest`,
 * `PlatformPackSchemaValidatesExistingPacksTest`,
 * `RuntimeRepoBrowserContractsGroupTest`, `PlatformPackSchemaViewerStateTest`).
 *
 * Published via the `java-test-fixtures` plugin on `runtime-core`, so any downstream module
 * can consume it via `testImplementation(testFixtures(project(":runtime-core")))`.
 */
public fun repoRootFromTest(): Path {
  var current: Path = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasContracts = Files.isDirectory(current.resolve("orchestration/contracts"))
    if (hasSettings && hasContracts) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
}
