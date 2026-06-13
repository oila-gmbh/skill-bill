package skillbill.install.apply

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.install.plan.platformSkills
import skillbill.install.staging.installedSkillsCacheRoot
import skillbill.install.support.InstallCleanupOperations
import skillbill.install.support.legacySkillBillCleanupNames
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal fun cleanupExistingSkillBillLinks(
  plan: InstallPlan,
  platformManifests: List<PlatformManifest>,
  failures: MutableList<InstallApplyIssue>,
) {
  if (!plan.request.replaceExistingSkillBillLinks) return
  val cleanupSkillNames = (
    plan.skills +
      platformManifests.flatMap(::platformSkills)
    )
    .map(InstallPlanSkill::name)
    .distinct()
  val legacyNames = legacySkillBillCleanupNames(cleanupSkillNames)
  val cleanupContext = InstallCleanupContext(
    skillNames = cleanupSkillNames,
    legacyNames = legacyNames,
    installedSkillsRoot = installedSkillsCacheRoot(plan.request.home),
  )
  plan.agents.forEach { agentTarget ->
    cleanupOneTarget(agentTarget.agent, agentTarget.path, cleanupContext, failures)
    // Migration: before SKILL-bill installed Claude skills into `<root>/skills`, it linked them into
    // the sibling `<root>/commands` slash-command dir. Sweep that legacy location so upgrading users
    // don't keep orphaned command symlinks pointing into the installed-skills cache.
    legacyClaudeCommandsDir(agentTarget.agent, agentTarget.path)?.let { legacyDir ->
      cleanupOneTarget(agentTarget.agent, legacyDir, cleanupContext, failures)
    }
  }
}

private data class InstallCleanupContext(
  val skillNames: List<String>,
  val legacyNames: List<String>,
  val installedSkillsRoot: Path,
)

private fun cleanupOneTarget(
  agent: InstallAgent,
  targetDir: Path,
  cleanupContext: InstallCleanupContext,
  failures: MutableList<InstallApplyIssue>,
) {
  runCatching {
    InstallCleanupOperations.cleanupAgentTarget(
      targetDir = targetDir,
      skillNames = cleanupContext.skillNames,
      legacyNames = cleanupContext.legacyNames,
      managedInstallMarker = ".skill-bill-install",
      installedSkillsRoot = cleanupContext.installedSkillsRoot,
    )
  }.getOrElse { error ->
    failures.add(
      InstallApplyIssue(
        kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
        message = error.message.orEmpty(),
        agent = agent,
        path = targetDir,
        causeClass = error::class.qualifiedName,
      ),
    )
  }
}

private fun legacyClaudeCommandsDir(agent: InstallAgent, targetDir: Path): Path? =
  if (agent == InstallAgent.CLAUDE && targetDir.fileName?.toString() == "skills") {
    targetDir.resolveSibling("commands")
  } else {
    null
  }
