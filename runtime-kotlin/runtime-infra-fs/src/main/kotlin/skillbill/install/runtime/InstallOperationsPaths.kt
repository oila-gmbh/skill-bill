package skillbill.install.runtime

import skillbill.install.plan.SUPPORTED_AGENTS
import skillbill.install.plan.agentPaths
import skillbill.install.support.claudeConfigRoot
import skillbill.install.support.claudeConfigRoots
import skillbill.install.support.codexConfigRoots
import java.nio.file.Path

internal object InstallOperationsPaths {
  fun agentPath(agent: String, home: Path? = null, environment: Map<String, String> = System.getenv()): Path {
    require(agent in SUPPORTED_AGENTS) {
      "Unknown agent '$agent'. Supported agents: ${SUPPORTED_AGENTS.joinToString(", ")}."
    }
    return agentPaths(home, environment).getValue(agent)
  }

  fun claudeRoots(home: Path? = null, environment: Map<String, String> = System.getenv()): List<Path> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    return claudeConfigRoots(resolvedHome, environment)
  }

  fun codexRoots(home: Path? = null, environment: Map<String, String> = System.getenv()): List<Path> {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    return codexConfigRoots(resolvedHome, environment)
  }

  fun claudeAgentsPath(home: Path? = null, environment: Map<String, String> = System.getenv()): Path {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    return claudeConfigRoot(resolvedHome, environment).resolve("agents")
  }

  fun junieAgentsPath(home: Path? = null): Path {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    return resolvedHome.resolve(".junie/agents")
  }

  fun cursorAgentsPath(home: Path? = null): Path {
    val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
    return resolvedHome.resolve(".cursor/agents")
  }
}
