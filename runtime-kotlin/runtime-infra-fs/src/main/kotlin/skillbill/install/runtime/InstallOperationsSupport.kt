package skillbill.install.runtime

import skillbill.install.model.AgentTarget
import skillbill.install.plan.InstallContext
import skillbill.install.plan.installSkill
import java.nio.file.Files
import java.nio.file.Path

internal fun linkInstalledSkill(
  source: Path,
  targetDir: Path,
  agent: String,
  repoRoot: Path?,
  home: Path?,
): List<Path> {
  val resolvedTargetDir = targetDir.toAbsolutePath().normalize()
  Files.createDirectories(resolvedTargetDir)
  return installSkill(
    skillPath = source,
    agentTargets = listOf(AgentTarget(agent.ifBlank { "manual" }, resolvedTargetDir)),
    context = InstallContext(
      repoRoot = repoRoot?.toAbsolutePath()?.normalize(),
      home = home ?: Path.of(System.getProperty("user.home")),
    ),
  )
}
