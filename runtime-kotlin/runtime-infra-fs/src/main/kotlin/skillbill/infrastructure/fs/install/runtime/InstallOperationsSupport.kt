package skillbill.infrastructure.fs.install.runtime

import skillbill.infrastructure.fs.install.plan.InstallContext
import skillbill.infrastructure.fs.install.plan.installSkill
import skillbill.install.model.AgentTarget
<<<<<<<< HEAD:runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/install/runtime/InstallOperationsSupport.kt
import skillbill.install.plan.InstallContext
import skillbill.install.plan.installSkill
========
import skillbill.ports.repository.toFileLocation
>>>>>>>> 9d724a13f (SKILL-233: Engine module and package roots):runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/install/runtime/InstallOperationsSkillLink.kt
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
