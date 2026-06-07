package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentTarget
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.OrchestrationLinkOutcome
import skillbill.install.model.OrchestrationLinkStatus
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun applyOrchestrationLinks(
  plan: InstallPlan,
  warnings: MutableList<InstallApplyIssue>,
): List<OrchestrationLinkOutcome> {
  val orchestrationSource = plan.request.repoRoot.resolve("orchestration").toAbsolutePath().normalize()
  if (!Files.isDirectory(orchestrationSource, LinkOption.NOFOLLOW_LINKS)) {
    return emptyList()
  }
  return plan.agents
    .distinctBy { agentTarget -> agentTarget.path.toAbsolutePath().normalize().parent }
    .map { agentTarget ->
      linkOrchestrationDir(
        agentTarget = agentTarget,
        orchestrationSource = orchestrationSource,
        warnings = warnings,
      )
    }
}

private fun linkOrchestrationDir(
  agentTarget: InstallAgentTarget,
  orchestrationSource: Path,
  warnings: MutableList<InstallApplyIssue>,
): OrchestrationLinkOutcome {
  val linkPath = agentTarget.path.toAbsolutePath().normalize().parent.resolve("orchestration").normalize()
  return runCatching {
    createOrSkipOrchestrationLink(agentTarget.agent, linkPath, orchestrationSource)
  }.getOrElse { error ->
    val symlinkError = error as? InstallSymlinkException
    val issue = InstallApplyIssue(
      kind = InstallApplyIssueKind.ORCHESTRATION_LINK_FAILED,
      message = error.message.orEmpty(),
      agent = agentTarget.agent,
      path = linkPath,
      guidance = symlinkError?.guidance,
      causeClass = error::class.qualifiedName,
    )
    warnings.add(issue)
    OrchestrationLinkOutcome(
      agent = agentTarget.agent,
      linkPath = linkPath,
      linkTarget = orchestrationSource,
      status = OrchestrationLinkStatus.FAILED,
      message = error.message.orEmpty(),
      issue = issue,
    )
  }
}

private fun createOrSkipOrchestrationLink(
  agent: InstallAgent,
  linkPath: Path,
  linkTarget: Path,
): OrchestrationLinkOutcome {
  if (Files.isSymbolicLink(linkPath)) {
    val existingTarget = resolveOrchestrationSymlinkTarget(linkPath)
    if (existingTarget == linkTarget) {
      return OrchestrationLinkOutcome(
        agent = agent,
        linkPath = linkPath,
        linkTarget = linkTarget,
        status = OrchestrationLinkStatus.SKIPPED,
        message = "already linked to $linkTarget",
      )
    }
    createReplacementSymlinkWithGuidance(linkPath, linkTarget)
  } else {
    require(!Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
      "Existing non-symlink path at $linkPath was preserved."
    }
    Files.createDirectories(linkPath.parent)
    createNewSymlinkWithGuidance(linkPath, linkTarget)
  }
  return OrchestrationLinkOutcome(
    agent = agent,
    linkPath = linkPath,
    linkTarget = linkTarget,
    status = OrchestrationLinkStatus.CREATED,
    message = "linked to $linkTarget",
  )
}

private fun resolveOrchestrationSymlinkTarget(linkPath: Path): Path? = runCatching {
  val rawTarget = Files.readSymbolicLink(linkPath)
  val resolvedTarget = if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget)
  resolvedTarget.toAbsolutePath().normalize()
}.getOrNull()
