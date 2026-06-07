package skillbill.install

import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkill
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files

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
  plan.agents.forEach { agentTarget ->
    runCatching {
      InstallCleanupOperations.cleanupAgentTarget(
        targetDir = agentTarget.path,
        skillNames = cleanupSkillNames,
        legacyNames = legacyNames,
        managedInstallMarker = ".skill-bill-install",
      )
    }.getOrElse { error ->
      failures.add(
        InstallApplyIssue(
          kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
          message = error.message.orEmpty(),
          agent = agentTarget.agent,
          path = agentTarget.path,
          causeClass = error::class.qualifiedName,
        ),
      )
    }
  }
  plan.agents
    .distinctBy { agentTarget -> agentTarget.path.toAbsolutePath().normalize().parent }
    .forEach { agentTarget ->
      val orchestrationLink = agentTarget.path.toAbsolutePath().normalize().parent.resolve("orchestration")
      runCatching {
        if (Files.isSymbolicLink(orchestrationLink)) {
          Files.deleteIfExists(orchestrationLink)
        }
      }.getOrElse { error ->
        failures.add(
          InstallApplyIssue(
            kind = InstallApplyIssueKind.ORCHESTRATION_LINK_FAILED,
            message = error.message.orEmpty(),
            agent = agentTarget.agent,
            path = orchestrationLink,
            causeClass = error::class.qualifiedName,
          ),
        )
      }
    }
}
