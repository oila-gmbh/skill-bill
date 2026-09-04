package skillbill.install

import java.nio.file.Files
import java.nio.file.Path

internal fun installTestEnvironment(home: Path): Map<String, String> {
  val environment = mutableMapOf("HOME" to home.toString())
  val codexHome = home.resolve(".codex")
  if (Files.isDirectory(codexHome)) {
    environment["CODEX_HOME"] = codexHome.toString()
  }
  val claudeHome = home.resolve(".claude")
  if (Files.isDirectory(claudeHome)) {
    environment["CLAUDE_CONFIG_DIR"] = claudeHome.toString()
  }
  return environment
}
