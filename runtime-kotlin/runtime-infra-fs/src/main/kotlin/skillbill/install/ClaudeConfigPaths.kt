package skillbill.install

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

internal const val CLAUDE_CONFIG_DIR_ENV: String = "CLAUDE_CONFIG_DIR"

private const val CLAUDE_PROFILE_PREFIX: String = ".claude-"

/**
 * Files/dirs whose presence marks a `~/.claude-<name>` directory as a real Claude Code profile root
 * rather than an unrelated dotfile that merely happens to share the prefix.
 */
private val CLAUDE_PROFILE_MARKERS: List<String> =
  listOf(".claude.json", ".credentials.json", "commands", "agents", "history.jsonl")

/**
 * Resolve the Claude Code config root the same way Claude Code itself does: honor the
 * `CLAUDE_CONFIG_DIR` environment variable when set (named accounts / work profiles point it at
 * `~/.claude-<name>`), falling back to `~/.claude`. Installing into the wrong root is why a skill
 * install under a non-default profile silently lands nowhere the agent reads.
 */
internal fun claudeConfigRoot(home: Path, environment: Map<String, String> = System.getenv()): Path =
  environment[CLAUDE_CONFIG_DIR_ENV]?.takeIf { it.isNotBlank() }
    ?.let { Path.of(it).toAbsolutePath().normalize() }
    ?: home.resolve(".claude")

/**
 * Discover every Claude Code config root skill-bill should install into: the default `~/.claude`
 * first, then any existing top-level `~/.claude-<name>` profile directories that look like real
 * Claude roots, then an explicit `CLAUDE_CONFIG_DIR` when it points somewhere distinct. Ordered and
 * deduped by normalized absolute path so multiple profiles fan out without double-installing.
 */
internal fun claudeConfigRoots(home: Path, environment: Map<String, String> = System.getenv()): List<Path> {
  val ordered = mutableListOf<Path>()
  val seen = mutableSetOf<Path>()

  fun add(path: Path) {
    val normalized = path.toAbsolutePath().normalize()
    if (seen.add(normalized)) {
      ordered.add(normalized)
    }
  }

  add(home.resolve(".claude"))

  if (Files.isDirectory(home)) {
    // A $HOME that is unreadable/unlistable (permissions, race) must not crash detection/planning;
    // fall back to the default + env contributions rather than propagating the IOException.
    runCatching {
      Files.list(home).use { stream ->
        stream
          .filter { entry -> Files.isDirectory(entry) }
          .filter { entry -> entry.fileName.toString().startsWith(CLAUDE_PROFILE_PREFIX) }
          .filter { entry -> hasClaudeProfileMarker(entry) }
          .sorted(compareBy { entry -> entry.fileName.toString() })
          .toList()
      }
    }.getOrDefault(emptyList()).forEach { entry -> add(entry) }
  }

  environment[CLAUDE_CONFIG_DIR_ENV]?.takeIf { it.isNotBlank() }?.let { explicit ->
    add(Path.of(explicit))
  }

  return ordered
}

internal fun claudeSkillTargets(home: Path? = null, environment: Map<String, String> = System.getenv()): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return claudeConfigRoots(resolvedHome, environment).map { root -> root.resolve("skills") }
}

private fun hasClaudeProfileMarker(root: Path): Boolean =
  CLAUDE_PROFILE_MARKERS.any { marker -> Files.exists(root.resolve(marker)) }
