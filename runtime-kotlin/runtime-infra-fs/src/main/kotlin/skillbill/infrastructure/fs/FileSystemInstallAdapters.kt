package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.install.InstallCleanupOperations
import skillbill.install.InstallNativeAgentOperations
import skillbill.install.InstallOperations
import skillbill.install.buildInstallStagingIntent
import skillbill.install.collectInstallPlanningFacts
import skillbill.install.installedSkillsCacheRoot
import skillbill.install.materializeSelectedPlatformSkills
import skillbill.launcher.McpRegistrationOperations
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsRequest
import skillbill.ports.install.agent.model.DetectInstallAgentTargetsResult
import skillbill.ports.install.agent.model.InstallAgentDirectoryRequest
import skillbill.ports.install.agent.model.InstallAgentDirectoryResult
import skillbill.ports.install.agent.model.InstallAgentPathRequest
import skillbill.ports.install.agent.model.InstallAgentPathResult
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupRequest
import skillbill.ports.install.agent.model.InstallAgentTargetCleanupResult
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.apply.model.InstallApplyExecutionRequest
import skillbill.ports.install.apply.model.InstallApplyExecutionResult
import skillbill.ports.install.link.InstallSkillLinkPort
import skillbill.ports.install.link.model.InstallSkillLinkRequest
import skillbill.ports.install.link.model.InstallSkillLinkResult
import skillbill.ports.install.mcp.InstallMcpRegistrationPort
import skillbill.ports.install.mcp.model.InstallMcpRegistrationRequest
import skillbill.ports.install.mcp.model.InstallMcpRegistrationResult
import skillbill.ports.install.mcp.model.InstallMcpUnregistrationRequest
import skillbill.ports.install.model.InstallCleanupResult
import skillbill.ports.install.model.NativeAgentLinkOutcome
import skillbill.ports.install.model.NativeAgentLinkProvider
import skillbill.ports.install.model.NativeAgentLinkRequest
import skillbill.ports.install.model.NativeAgentSkippedLink
import skillbill.ports.install.nativeagent.InstallNativeAgentLinkPort
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationRequest
import skillbill.ports.install.nativeagent.model.InstallNativeAgentLinkOperationResult
import skillbill.ports.install.nativeagent.model.InstallNativeAgentUnlinkOperationResult
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.install.plan.model.InstallPlanningFactsRequest
import skillbill.ports.install.plan.model.InstallPlanningFactsResult
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortRequest
import skillbill.ports.install.plan.model.InstallPlatformSkillMaterializationPortResult
import skillbill.ports.install.plan.model.InstallStagingIntentRequest
import skillbill.ports.install.plan.model.InstallStagingIntentResult
import skillbill.install.NativeAgentLinkOverrides as FsNativeAgentLinkOverrides
import skillbill.install.NativeAgentLinkRequest as FsNativeAgentLinkRequest

@Inject
class FileSystemInstallPlanningFacts : InstallPlanningFactsPort {
  override fun collectPlanningFacts(request: InstallPlanningFactsRequest): InstallPlanningFactsResult =
    InstallPlanningFactsResult(
      facts = collectInstallPlanningFacts(request.installRequest),
    )
}

@Inject
class FileSystemInstallPlatformSkillMaterialization : InstallPlatformSkillMaterializationPort {
  override fun materializePlatformSkills(
    request: InstallPlatformSkillMaterializationPortRequest,
  ): InstallPlatformSkillMaterializationPortResult = InstallPlatformSkillMaterializationPortResult(
    platformPacks = materializeSelectedPlatformSkills(
      platformManifests = request.platformManifests,
      selectedPlatformSlugs = request.selectedPlatformSlugs,
    ),
  )
}

@Inject
class FileSystemInstallStagingIntent : InstallStagingIntentPort {
  override fun buildStagingIntent(request: InstallStagingIntentRequest): InstallStagingIntentResult =
    InstallStagingIntentResult(
      staging = buildInstallStagingIntent(
        request = request.installRequest,
        draftSkills = request.draft.skills,
        platformManifests = request.platformManifests,
      ),
    )
}

@Inject
class FileSystemInstallApplyExecution : InstallApplyExecutionPort {
  override fun applyInstall(request: InstallApplyExecutionRequest): InstallApplyExecutionResult =
    InstallApplyExecutionResult(
      result = InstallOperations.applyInstall(request.plan, request.telemetryLevelMutator),
    )
}

@Inject
class FileSystemInstallSkillLink : InstallSkillLinkPort {
  override fun linkSkill(request: InstallSkillLinkRequest): InstallSkillLinkResult = InstallSkillLinkResult(
    linkedPaths = InstallOperations.linkSkill(
      source = request.source,
      targetDir = request.targetDir,
      agent = request.agent,
      repoRoot = request.repoRoot,
      home = request.home,
    ),
  )
}

@Inject
class FileSystemInstallAgentTargets : InstallAgentTargetPort {
  override fun agentPath(request: InstallAgentPathRequest): InstallAgentPathResult =
    InstallAgentPathResult(InstallOperations.agentPath(request.agent, request.home))

  override fun detectAgentTargets(request: DetectInstallAgentTargetsRequest): DetectInstallAgentTargetsResult =
    DetectInstallAgentTargetsResult(InstallOperations.detectAgentTargets(request.home))

  override fun agentDirectory(request: InstallAgentDirectoryRequest): InstallAgentDirectoryResult =
    InstallAgentDirectoryResult(
      when (request.agent) {
        "codex" -> InstallOperations.codexAgentsPath(request.home)
        "claude" -> InstallOperations.claudeAgentsPath(request.home)
        "opencode" -> InstallOperations.opencodeAgentsPath(request.home)
        "junie" -> InstallOperations.junieAgentsPath(request.home)
        else -> InstallOperations.agentPath(request.agent, request.home)
      },
    )

  override fun cleanupAgentTarget(request: InstallAgentTargetCleanupRequest): InstallAgentTargetCleanupResult {
    val (removed, skipped) = InstallCleanupOperations.cleanupAgentTarget(
      targetDir = request.targetDir,
      skillNames = request.skillNames,
      legacyNames = request.legacyNames,
      managedInstallMarker = request.managedInstallMarker,
      installedSkillsRoot = request.home?.let { installedSkillsCacheRoot(it) },
    )
    return InstallAgentTargetCleanupResult(
      cleanup = InstallCleanupResult(removed = removed, skipped = skipped),
    )
  }
}

@Inject
class FileSystemInstallNativeAgentLinks : InstallNativeAgentLinkPort {
  override fun linkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentLinkOperationResult {
    val fsRequest = request.linkRequest.toFsRequest()
    val outcome = when (request.provider) {
      NativeAgentLinkProvider.CLAUDE -> InstallNativeAgentOperations.linkClaudeAgents(fsRequest)
      NativeAgentLinkProvider.CODEX -> InstallNativeAgentOperations.linkCodexAgents(fsRequest)
      NativeAgentLinkProvider.OPENCODE -> InstallNativeAgentOperations.linkOpencodeAgents(fsRequest)
      NativeAgentLinkProvider.JUNIE -> InstallNativeAgentOperations.linkJunieAgents(fsRequest)
    }
    return InstallNativeAgentLinkOperationResult(
      outcome = NativeAgentLinkOutcome(
        linked = outcome.linked,
        skipped = outcome.skipped.map { skip -> NativeAgentSkippedLink(skip.path, skip.reason) },
      ),
    )
  }

  override fun unlinkNativeAgents(
    request: InstallNativeAgentLinkOperationRequest,
  ): InstallNativeAgentUnlinkOperationResult {
    val fsRequest = request.linkRequest.toFsRequest()
    return InstallNativeAgentUnlinkOperationResult(
      unlinked = when (request.provider) {
        NativeAgentLinkProvider.CLAUDE -> InstallNativeAgentOperations.unlinkClaudeAgents(fsRequest)
        NativeAgentLinkProvider.CODEX -> InstallNativeAgentOperations.unlinkCodexAgents(fsRequest)
        NativeAgentLinkProvider.OPENCODE -> InstallNativeAgentOperations.unlinkOpencodeAgents(fsRequest)
        NativeAgentLinkProvider.JUNIE -> InstallNativeAgentOperations.unlinkJunieAgents(fsRequest)
      },
    )
  }
}

@Inject
class FileSystemInstallMcpRegistration : InstallMcpRegistrationPort {
  override fun registerMcp(request: InstallMcpRegistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      mutation = McpRegistrationOperations.register(request.agent, request.runtimeMcpBin, request.home),
    )

  override fun unregisterMcp(request: InstallMcpUnregistrationRequest): InstallMcpRegistrationResult =
    InstallMcpRegistrationResult(
      mutation = McpRegistrationOperations.unregister(request.agent, request.home),
    )
}

private fun NativeAgentLinkRequest.toFsRequest(): FsNativeAgentLinkRequest = FsNativeAgentLinkRequest(
  platformPacksRoot = platformPacksRoot,
  skillsRoot = skillsRoot,
  home = home,
  selectedPlatforms = selectedPlatforms,
  overrides = FsNativeAgentLinkOverrides(
    installCacheRoot = overrides.installCacheRoot,
    sourceRoots = overrides.sourceRoots,
    legacyManagedRoot = overrides.legacyManagedRoot,
  ),
)
