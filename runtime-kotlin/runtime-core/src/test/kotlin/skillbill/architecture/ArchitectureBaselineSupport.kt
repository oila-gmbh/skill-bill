package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path

internal object ArchitectureBaselineSupport {
  private val baselineRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { start ->
      var dir: Path? = start
      while (dir != null) {
        val candidate = dir.resolve(
          "runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/baselines",
        )
        if (Files.isDirectory(candidate)) return@let candidate
        dir = dir.parent
      }
      start.resolve("runtime-kotlin/runtime-core/src/test/kotlin/skillbill/architecture/baselines")
    }

  fun readBaseline(name: String): String = Files.readString(baselineRoot.resolve(name))
}
