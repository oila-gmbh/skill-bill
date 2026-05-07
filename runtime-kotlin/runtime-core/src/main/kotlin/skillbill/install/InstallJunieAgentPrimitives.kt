package skillbill.install

import java.nio.file.Path

internal fun discoverJunieAgentMarkdown(
  platformPacksRoot: Path,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> = discoverNativeAgentFiles(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  selectedPlatforms = selectedPlatforms,
  directoryName = "junie-agents",
  extension = "md",
)

internal fun uninstallJunieAgentMarkdown(
  platformPacksRoot: Path,
  home: Path? = null,
  skillsRoot: Path? = null,
  selectedPlatforms: List<String>? = null,
): List<Path> {
  val resolvedHome = home ?: Path.of(System.getProperty("user.home"))
  return uninstallNativeAgentFiles(
    discoverJunieAgentMarkdown(platformPacksRoot, skillsRoot, selectedPlatforms),
    listOf(resolvedHome.resolve(".junie/agents")),
  )
}
