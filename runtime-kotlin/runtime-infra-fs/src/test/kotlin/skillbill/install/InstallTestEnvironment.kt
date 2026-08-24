package skillbill.install

import java.nio.file.Files
import java.nio.file.Path

internal fun installTestEnvironment(home: Path): Map<String, String> {
  val filtered = System.getenv().filterKeys { key ->
    key != "CODEX_HOME" && key != "CLAUDE_CONFIG_DIR"
  }
  val codexHome = home.resolve(".codex")
  return if (Files.isDirectory(codexHome)) {
    filtered + ("CODEX_HOME" to codexHome.toString())
  } else {
    filtered
  }
}
