package skillbill.install.support

import java.nio.file.Path
import skillbill.nativeagent.support.claudeConfigRoots as nativeAgentClaudeConfigRoots

internal const val CLAUDE_CONFIG_DIR_ENV: String = "CLAUDE_CONFIG_DIR"

internal fun claudeConfigRoot(home: Path, environment: Map<String, String> = System.getenv()): Path =
  environment[CLAUDE_CONFIG_DIR_ENV]?.takeIf { it.isNotBlank() }
    ?.let { Path.of(it).toAbsolutePath().normalize() }
    ?: home.resolve(".claude")

internal fun claudeConfigRoots(home: Path, environment: Map<String, String> = System.getenv()): List<Path> =
  nativeAgentClaudeConfigRoots(home, environment)

internal fun claudeSkillTargets(home: Path? = null, environment: Map<String, String> = System.getenv()): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return claudeConfigRoots(resolvedHome, environment).map { root -> root.resolve("skills") }
}
