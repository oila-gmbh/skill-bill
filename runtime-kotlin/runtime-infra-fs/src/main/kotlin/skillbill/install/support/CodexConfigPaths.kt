package skillbill.install.support

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

internal const val CODEX_HOME_ENV: String = "CODEX_HOME"

private const val CODEX_PROFILE_PREFIX: String = ".codex-"

private val CODEX_PROFILE_MARKERS: List<String> =
  listOf("config.toml", "history.jsonl", "installation_id", "router.config.toml")

internal fun codexConfigRoot(home: Path, environment: Map<String, String> = System.getenv()): Path =
  environment[CODEX_HOME_ENV]?.takeIf { it.isNotBlank() }
    ?.let { Path.of(it).toAbsolutePath().normalize() }
    ?: defaultCodexRoot(home)

internal fun codexConfigRoots(home: Path, environment: Map<String, String> = System.getenv()): List<Path> {
  val ordered = mutableListOf<Path>()
  val seen = mutableSetOf<Path>()

  fun add(path: Path) {
    val normalized = path.toAbsolutePath().normalize()
    if (seen.add(normalized)) {
      ordered.add(normalized)
    }
  }

  val defaultCodex = home.resolve(".codex")
  if (Files.exists(defaultCodex)) {
    add(defaultCodex)
  }

  if (Files.isDirectory(home)) {
    runCatching {
      Files.list(home).use { stream ->
        stream
          .filter { entry -> Files.isDirectory(entry) }
          .filter { entry -> entry.fileName.toString().startsWith(CODEX_PROFILE_PREFIX) }
          .filter { entry -> hasCodexProfileMarker(entry) }
          .sorted(compareBy { entry -> entry.fileName.toString() })
          .toList()
      }
    }.getOrDefault(emptyList()).forEach { entry -> add(entry) }
  }

  environment[CODEX_HOME_ENV]?.takeIf { it.isNotBlank() }?.let { explicit ->
    add(Path.of(explicit))
  }

  return ordered
}

internal fun codexSkillTargets(home: Path? = null, environment: Map<String, String> = System.getenv()): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  val ordered = mutableListOf<Path>()
  val seen = mutableSetOf<Path>()

  fun add(path: Path) {
    val normalized = path.toAbsolutePath().normalize()
    if (seen.add(normalized)) {
      ordered.add(normalized)
    }
  }

  codexConfigRoots(resolvedHome, environment).forEach { root -> add(root.resolve("skills")) }
  add(resolvedHome.resolve(".agents/skills"))
  return ordered
}

private fun defaultCodexRoot(home: Path): Path {
  val codexRoot = home.resolve(".codex")
  return if (Files.exists(codexRoot)) codexRoot else home.resolve(".agents")
}

private fun hasCodexProfileMarker(root: Path): Boolean =
  CODEX_PROFILE_MARKERS.any { marker -> Files.exists(root.resolve(marker)) }
