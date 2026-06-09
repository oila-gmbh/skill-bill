package skillbill.install

import java.nio.file.Path

internal const val CLAUDE_CONFIG_DIR_ENV: String = "CLAUDE_CONFIG_DIR"

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
