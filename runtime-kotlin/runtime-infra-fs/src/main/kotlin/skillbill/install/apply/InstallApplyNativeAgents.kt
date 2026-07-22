package skillbill.install.apply

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.NativeAgentApplyOutcome
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.NativeAgentProviderId
import skillbill.install.nativeagent.InstallNativeAgentOperations
import skillbill.install.nativeagent.NativeAgentLinkOutcome
import skillbill.install.nativeagent.NativeAgentLinkOverrides
import skillbill.install.nativeagent.NativeAgentLinkRequest
import skillbill.install.staging.installedSkillsCacheRoot
import skillbill.install.support.InstallSymlinkException
import skillbill.nativeagent.rendering.NativeAgentOperations
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
    sourceRoots = nativeAgentSourceRoots(plan.skills, plan.selectedPlatformSlugs.toSet()),
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
  val unlink: (NativeAgentLinkRequest) -> List<Path>,
)

private fun nativeAgentApplyCacheRoot(plan: InstallPlan): Path = currentNativeAgentApplyCacheRoot(
  plan.request.home,
  plan.installationTargetPaths.platformPacksRoot,
  plan.installationTargetPaths.skillsRoot,
)

fun currentNativeAgentApplyCacheRoot(home: Path, platformPacksRoot: Path, skillsRoot: Path?): Path {
  val cacheLeaf = NativeAgentOperations.installCacheRoot(home, platformPacksRoot, skillsRoot).fileName.toString()
  return installedSkillsCacheRoot(home).resolve("native-agents-$cacheLeaf").toAbsolutePath().normalize()
}

private fun nativeAgentLegacyCacheRoot(plan: InstallPlan): Path = NativeAgentOperations.installCacheRoot(
  home = plan.request.home,
  platformPacksRoot = plan.installationTargetPaths.platformPacksRoot,
  skillsRoot = plan.installationTargetPaths.skillsRoot,
)

// Unlike standaloneInstallableSkills, internal skills stay enumerated here: a native-agents
// bundle hosted in an internal skill's dir installs exactly as it does for a listed skill
// (native-agent parity). Do not add an internalFor filter.
internal fun nativeAgentSourceRoots(
  skills: List<skillbill.install.model.InstallPlanSkill>,
  selectedPlatformSlugs: Set<String>,
): List<Path> = skills
  .filter { skill -> skill.platformSlug == null || skill.platformSlug in selectedPlatformSlugs }
  .map { skill -> skill.sourceDir }

private val nativeAgentInstallers: List<NativeAgentInstaller> = listOf(
  NativeAgentInstaller(
    agent = InstallAgent.CLAUDE,
    provider = NativeAgentProviderId.CLAUDE,
    link = InstallNativeAgentOperations::linkClaudeAgents,
    unlink = InstallNativeAgentOperations::unlinkClaudeAgents,
  ),
  NativeAgentInstaller(
    agent = InstallAgent.CODEX,
    provider = NativeAgentProviderId.CODEX,
    link = InstallNativeAgentOperations::linkCodexAgents,
    unlink = InstallNativeAgentOperations::unlinkCodexAgents,
  ),
  NativeAgentInstaller(
    agent = InstallAgent.OPENCODE,
    provider = NativeAgentProviderId.OPENCODE,
    link = InstallNativeAgentOperations::linkOpencodeAgents,
    unlink = InstallNativeAgentOperations::unlinkOpencodeAgents,
  ),
  NativeAgentInstaller(
    agent = InstallAgent.JUNIE,
    provider = NativeAgentProviderId.JUNIE,
    link = InstallNativeAgentOperations::linkJunieAgents,
    unlink = InstallNativeAgentOperations::unlinkJunieAgents,
  ),
  NativeAgentInstaller(
    agent = InstallAgent.ZCODE,
    provider = NativeAgentProviderId.ZCODE,
    link = InstallNativeAgentOperations::linkZcodeAgents,
    unlink = InstallNativeAgentOperations::unlinkZcodeAgents,
  ),
)
