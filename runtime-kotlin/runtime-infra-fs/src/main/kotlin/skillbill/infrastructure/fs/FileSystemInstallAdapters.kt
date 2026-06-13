package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.install.nativeagent.InstallNativeAgentOperations
import skillbill.install.plan.buildInstallStagingIntent
import skillbill.install.plan.codexAgentsPath
import skillbill.install.plan.collectInstallPlanningFacts
import skillbill.install.plan.materializeSelectedPlatformSkills
import skillbill.install.plan.opencodeAgentsPath
import skillbill.install.reconcile.ReconcileSourceRoots
import skillbill.install.reconcile.applyReconciliation
import skillbill.install.reconcile.computeReconciliationPlan
import skillbill.install.runtime.InstallOperations
import skillbill.install.staging.installedSkillsCacheRoot
import skillbill.install.support.InstallCleanupOperations
import skillbill.launcher.mcp.McpRegistrationOperations
import skillbill.ports.install.agent.InstallAgentTargetPort
import skillbill.ports.install.agent.model.ClaudeConfigRootsRequest
import skillbill.ports.install.agent.model.ClaudeConfigRootsResult
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
import skillbill.ports.install.baseline.BaselineManifestPersistencePort
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
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
import skillbill.ports.install.reconcile.InstallReconcileApplyPort
import skillbill.ports.install.reconcile.InstallReconcilePort
import skillbill.ports.install.reconcile.model.InstallReconcileApplyRequest
import skillbill.ports.install.reconcile.model.InstallReconcileApplyResult
import skillbill.ports.install.reconcile.model.InstallReconcileRequest
import skillbill.ports.install.reconcile.model.InstallReconcileResult
import skillbill.install.nativeagent.NativeAgentLinkOverrides as FsNativeAgentLinkOverrides
import skillbill.install.nativeagent.NativeAgentLinkRequest as FsNativeAgentLinkRequest

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
class FileSystemInstallReconcile(
  private val baselineManifestPersistence: BaselineManifestPersistencePort,
) : InstallReconcilePort {
  override fun reconcile(request: InstallReconcileRequest): InstallReconcileResult {
    val baseline = baselineManifestPersistence
      .readBaseline(ReadBaselineManifestRequest(installHome = request.home))
      .manifest
    return InstallReconcileResult(
      plan = computeReconciliationPlan(
        upstream = ReconcileSourceRoots(
          repoRoot = request.upstreamRepoRoot,
          skillsRoot = request.upstreamSkillsRoot,
          platformPacksRoot = request.upstreamPlatformPacksRoot,
        ),
        local = ReconcileSourceRoots(
          repoRoot = request.localRepoRoot,
          skillsRoot = request.localSkillsRoot,
          platformPacksRoot = request.localPlatformPacksRoot,
        ),
        home = request.home,
        baseline = baseline,
      ),
    )
  }
}

@Inject
class FileSystemInstallReconcileApply(
  private val baselineManifestPersistence: BaselineManifestPersistencePort,
) : InstallReconcileApplyPort {
  override fun apply(request: InstallReconcileApplyRequest): InstallReconcileApplyResult {
    val baseline = baselineManifestPersistence
      .readBaseline(ReadBaselineManifestRequest(installHome = request.home))
      .manifest
    // Per-skill FILE operations against the live tree (gated on conflicts inside the
    // policy). The baseline refresh derives from the SAME returned plan, in the
    // application overlay, so the refresh-eligibility rule lives in ONE place.
    val output = applyReconciliation(
      upstream = ReconcileSourceRoots(
        repoRoot = request.upstreamRepoRoot,
        skillsRoot = request.upstreamSkillsRoot,
        platformPacksRoot = request.upstreamPlatformPacksRoot,
      ),
      local = ReconcileSourceRoots(
        repoRoot = request.localRepoRoot,
        skillsRoot = request.localSkillsRoot,
        platformPacksRoot = request.localPlatformPacksRoot,
      ),
      home = request.home,
      baseline = baseline,
      acceptConflicts = request.acceptConflicts,
    )
    return InstallReconcileApplyResult(
      plan = output.plan,
      installedPaths = output.installedPaths,
    )
  }
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

  override fun claudeConfigRoots(request: ClaudeConfigRootsRequest): ClaudeConfigRootsResult =
    ClaudeConfigRootsResult(InstallOperations.claudeRoots(request.home, request.environment))

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
