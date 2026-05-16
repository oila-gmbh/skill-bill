package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.NativeAgentApplyOutcome
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.nativeagent.NativeAgentOperations
import java.nio.file.Path

internal fun applyNativeAgents(
  plan: InstallPlan,
  failures: MutableList<InstallApplyIssue>,
): List<NativeAgentApplyOutcome> {
  val selectedAgents = plan.agents.map { target -> target.agent }.toSet()
  val context = NativeAgentApplyContext(
    plan = plan,
    failures = failures,
    installCacheRoot = nativeAgentApplyCacheRoot(plan),
    legacyManagedRoot = nativeAgentLegacyCacheRoot(plan),
    sourceRoots = selectedNativeAgentSourceRoots(plan),
  )
  return nativeAgentInstallers
    .filter { installer -> installer.agent in selectedAgents }
    .flatMap { installer ->
      applyNativeAgentProvider(
        installer = installer,
        context = context,
      )
    }
}

private fun applyNativeAgentProvider(
  installer: NativeAgentInstaller,
  context: NativeAgentApplyContext,
): List<NativeAgentApplyOutcome> = runCatching {
  installer.link(nativeAgentLinkRequest(context))
}.fold(
  onSuccess = { outcome -> nativeAgentProviderOutcomes(installer, outcome) },
  onFailure = { error ->
    listOf(
      failedNativeAgentOutcome(installer, error).also { nativeOutcome ->
        nativeOutcome.issue?.let(context.failures::add)
      },
    )
  },
)

private data class NativeAgentApplyContext(
  val plan: InstallPlan,
  val failures: MutableList<InstallApplyIssue>,
  val installCacheRoot: Path,
  val legacyManagedRoot: Path,
  val sourceRoots: List<Path>,
)

private fun nativeAgentLinkRequest(context: NativeAgentApplyContext): NativeAgentLinkRequest {
  val plan = context.plan
  return NativeAgentLinkRequest(
    platformPacksRoot = plan.installationTargetPaths.platformPacksRoot,
    skillsRoot = plan.installationTargetPaths.skillsRoot,
    home = plan.request.home,
    selectedPlatforms = plan.selectedPlatformSlugs,
    overrides = NativeAgentLinkOverrides(
      installCacheRoot = context.installCacheRoot,
      sourceRoots = context.sourceRoots,
      legacyManagedRoot = context.legacyManagedRoot,
    ),
  )
}

private fun nativeAgentProviderOutcomes(
  installer: NativeAgentInstaller,
  outcome: NativeAgentLinkOutcome,
): List<NativeAgentApplyOutcome> {
  val linked = outcome.linked.map { link ->
    NativeAgentApplyOutcome(
      provider = installer.provider,
      agent = installer.agent,
      status = NativeAgentApplyStatus.LINKED,
      path = link,
      message = "linked",
    )
  }
  val skipped = outcome.skipped.map { skipped ->
    NativeAgentApplyOutcome(
      provider = installer.provider,
      agent = installer.agent,
      status = NativeAgentApplyStatus.SKIPPED,
      path = skipped.path,
      message = skipped.reason,
    )
  }
  return (linked + skipped).ifEmpty {
    listOf(
      NativeAgentApplyOutcome(
        provider = installer.provider,
        agent = installer.agent,
        status = NativeAgentApplyStatus.SKIPPED,
        message = "no native-agent target or artifacts available for selected plan",
      ),
    )
  }
}

private fun failedNativeAgentOutcome(installer: NativeAgentInstaller, error: Throwable): NativeAgentApplyOutcome {
  val symlinkError = error as? InstallSymlinkException
  val issue = InstallApplyIssue(
    kind = InstallApplyIssueKind.NATIVE_AGENT_LINK_FAILED,
    message = error.message.orEmpty(),
    agent = installer.agent,
    path = symlinkError?.linkPath,
    guidance = symlinkError?.guidance,
    causeClass = error::class.qualifiedName,
  )
  return NativeAgentApplyOutcome(
    provider = installer.provider,
    agent = installer.agent,
    status = NativeAgentApplyStatus.FAILED,
    path = symlinkError?.linkPath,
    message = error.message.orEmpty(),
    issue = issue,
  )
}

private data class NativeAgentInstaller(
  val agent: InstallAgent,
  val provider: NativeAgentProviderId,
  val link: (NativeAgentLinkRequest) -> NativeAgentLinkOutcome,
)

private fun nativeAgentApplyCacheRoot(plan: InstallPlan): Path {
  val legacyCacheLeaf = NativeAgentOperations
    .installCacheRoot(
      home = plan.request.home,
      platformPacksRoot = plan.installationTargetPaths.platformPacksRoot,
      skillsRoot = plan.installationTargetPaths.skillsRoot,
    )
    .fileName
    .toString()
  return installedSkillsCacheRoot(plan.request.home).resolve("native-agents-$legacyCacheLeaf").normalize()
}

private fun nativeAgentLegacyCacheRoot(plan: InstallPlan): Path = NativeAgentOperations.installCacheRoot(
  home = plan.request.home,
  platformPacksRoot = plan.installationTargetPaths.platformPacksRoot,
  skillsRoot = plan.installationTargetPaths.skillsRoot,
)

private fun selectedNativeAgentSourceRoots(plan: InstallPlan): List<Path> {
  val selectedPlatformSlugs = plan.selectedPlatformSlugs.toSet()
  return plan.skills
    .filter { skill -> skill.platformSlug == null || skill.platformSlug in selectedPlatformSlugs }
    .map { skill -> skill.sourceDir }
}

private val nativeAgentInstallers: List<NativeAgentInstaller> = listOf(
  NativeAgentInstaller(
    agent = InstallAgent.CLAUDE,
    provider = NativeAgentProviderId.CLAUDE,
    link = InstallNativeAgentOperations::linkClaudeAgents,
  ),
  NativeAgentInstaller(
    agent = InstallAgent.CODEX,
    provider = NativeAgentProviderId.CODEX,
    link = InstallNativeAgentOperations::linkCodexAgents,
  ),
  NativeAgentInstaller(
    agent = InstallAgent.OPENCODE,
    provider = NativeAgentProviderId.OPENCODE,
    link = InstallNativeAgentOperations::linkOpencodeAgents,
  ),
  NativeAgentInstaller(
    agent = InstallAgent.JUNIE,
    provider = NativeAgentProviderId.JUNIE,
    link = InstallNativeAgentOperations::linkJunieAgents,
  ),
)
