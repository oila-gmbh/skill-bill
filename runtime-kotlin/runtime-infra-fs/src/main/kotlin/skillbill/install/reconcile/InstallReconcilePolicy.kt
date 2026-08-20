@file:Suppress("ReturnCount")

package skillbill.install.reconcile

import skillbill.agentaddon.discoverAgentAddons
import skillbill.error.ReconciliationConflictError
import skillbill.install.model.BaselineManifest
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.SkillReconciliationOutcome
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.install.plan.discoverPlatformManifests
import skillbill.install.plan.enumerateInstallPlanSkills
import skillbill.install.staging.INSTALL_CACHE_KEY_BYTES
import skillbill.install.staging.InstallContentHashInputs
import skillbill.install.staging.InternalStagingPreparation
import skillbill.install.staging.agentAddonPointersForSkill
import skillbill.install.staging.applicablePointers
import skillbill.install.staging.authoredFilesFor
import skillbill.install.staging.authoredStagingNames
import skillbill.install.staging.computeInstallContentHash
import skillbill.install.staging.generatedSupportPointersFor
import skillbill.install.staging.prepareInternalStaging
import skillbill.install.staging.validateAgentAddonPointerNamespace
import skillbill.scaffold.model.PlatformManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * SKILL-76 Subtask 2: per-skill reconcile hash-compare POLICY. Kept in
 * `runtime-infra-fs` because it reuses the module-internal staging helpers
 * (`applicablePointers` / `generatedSupportPointersFor` / `authoredFilesFor` /
 * `computeInstallContentHash`) that key the install staging leaf. This is the SAME
 * hash, never a second scheme.
 *
 * Enumeration: skills are discovered via [InstallPlanPolicy.buildPlanDraft] over a
 * synthetic ALL-platform request for each source root, exactly like the install
 * planning seam. For each skill-relative path (the skill `sourceDir` relativized
 * against its repo root), the UPSTREAM (candidate/clone source root) and LOCAL (copied
 * `~/.skill-bill` source root) hashes are computed. The BASELINE (manifest last-copied-in)
 * hash is carried through for recording only; it no longer influences classification.
 *
 * Classification (upstream always wins):
 *  - no upstream counterpart, `agent-addons/` -> locally-authored (user-owned, preserved)
 *  - no upstream counterpart, otherwise       -> prune (delete; the installed tree mirrors source)
 *  - upstream hash == local hash              -> unchanged (no file op, baseline recorded)
 *  - otherwise                                -> adopt (install upstream, record baseline)
 *
 * Local enumeration tolerates stale `contract_version` values in preserved platform-pack
 * manifests because upstream always replaces them on adopt; upstream enumeration stays strict.
 *
 * Idempotent: identical upstream and local inputs yield only unchanged outcomes and no
 * baseline change.
 */
internal const val SKILLS_PREFIX = "skills/"
internal const val PLATFORM_PACKS_PREFIX = "platform-packs/"
internal const val AGENT_ADDONS_PREFIX = "agent-addons/"

internal data class ReconcileSourceRoots(
  val repoRoot: Path,
  val skillsRoot: Path,
  val platformPacksRoot: Path,
)

internal enum class ReconcileSourceSide {
  UPSTREAM,
  LOCAL,
}

private fun ReconcileSourceSide.enforcesPlatformPackContractVersion(): Boolean = this == ReconcileSourceSide.UPSTREAM

/**
 * One enumerated skill: its content hash plus the on-disk skill directory it was
 * enumerated from. The directory lets the APPLY map a skill-relative path back to the
 * concrete live/upstream dir to replace, without reconstructing paths from string keys.
 */
internal data class ReconcileSkillEntry(
  val hash: String,
  val sourceDir: Path,
)

/** Result of the runtime-owned per-skill apply: the computed plan + the paths installed. */
internal data class ReconcileApplyOutput(
  val plan: ReconciliationPlan,
  val installedPaths: List<String>,
  val prunedPaths: List<String>,
)

internal fun computeReconciliationPlan(
  upstream: ReconcileSourceRoots,
  local: ReconcileSourceRoots,
  home: Path,
  baseline: BaselineManifest,
): ReconciliationPlan {
  val upstreamSkills = enumerateSkills(upstream, home, ReconcileSourceSide.UPSTREAM)
  val localSkills = enumerateSkills(local, home, ReconcileSourceSide.LOCAL)
  return classifyReconciliation(upstreamSkills, localSkills, baseline)
}

internal fun classifyReconciliation(
  upstreamSkills: Map<String, ReconcileSkillEntry>,
  localSkills: Map<String, ReconcileSkillEntry>,
  baseline: BaselineManifest,
): ReconciliationPlan {
  val skillPaths = (upstreamSkills.keys + localSkills.keys).toSortedSet()
  val outcomes = skillPaths.map { skillRelativePath ->
    classifySkill(
      skillRelativePath = skillRelativePath,
      upstreamHash = upstreamSkills[skillRelativePath]?.hash,
      localHash = localSkills[skillRelativePath]?.hash,
      baselineHash = baseline.hashFor(skillRelativePath),
    )
  }
  return ReconciliationPlan(outcomes = outcomes)
}

private fun classifySkill(
  skillRelativePath: String,
  upstreamHash: String?,
  localHash: String?,
  baselineHash: String?,
): SkillReconciliationOutcome {
  if (upstreamHash == null) {
    if (localHash == null) {
      throw ReconciliationConflictError(
        skillRelativePath = skillRelativePath,
        reason = "skill is present in neither the upstream nor the local source tree.",
      )
    }
    return if (skillRelativePath.startsWith(AGENT_ADDONS_PREFIX)) {
      SkillReconciliationOutcome.LocallyAuthored(
        skillRelativePath = skillRelativePath,
        localHash = localHash,
        baselineHash = baselineHash,
      )
    } else {
      SkillReconciliationOutcome.Prune(
        skillRelativePath = skillRelativePath,
        localHash = localHash,
        baselineHash = baselineHash,
      )
    }
  }
  if (localHash == upstreamHash) {
    return SkillReconciliationOutcome.Unchanged(
      skillRelativePath = skillRelativePath,
      upstreamHash = upstreamHash,
      baselineHash = baselineHash,
    )
  }
  return SkillReconciliationOutcome.Adopt(
    skillRelativePath = skillRelativePath,
    upstreamHash = upstreamHash,
    localHash = localHash,
    baselineHash = baselineHash,
  )
}

/**
 * Enumerate every skill under [roots] and map its skill-relative path -> ([content
 * hash] + on-disk skill dir). Returns an empty map when a source root is absent (e.g. a
 * fresh install with no copied `~/.skill-bill` source yet) so reconciliation classifies
 * upstream skills as adopt rather than failing. The skill dir is carried so the
 * APPLY can replace the live dir from the upstream dir without rebuilding paths.
 */
internal fun enumerateSkills(
  roots: ReconcileSourceRoots,
  home: Path,
  sourceSide: ReconcileSourceSide,
): Map<String, ReconcileSkillEntry> {
  val enforceContractVersion = sourceSide.enforcesPlatformPackContractVersion()
  val skillEntries = if (Files.isDirectory(roots.skillsRoot)) {
    val request = reconcileEnumerationRequest(roots, home)
    val platformManifests = discoverPlatformManifests(roots.platformPacksRoot, enforceContractVersion)
    // Reuse the approved builder seam for skill enumeration so this policy never
    // references the domain InstallPlanPolicy directly (adapter-ownership rule).
    val skills = enumerateInstallPlanSkills(request, enforceContractVersion)
    val selectedPackSkills = skills.filter { candidate ->
      candidate.kind == InstallPlanSkillKind.PLATFORM_PACK && candidate.internalFor != null
    }
    skills.associate { skill ->
      skillRelativePath(roots, skill) to ReconcileSkillEntry(
        hash = reconcileSkillHash(
          roots,
          skill,
          platformManifests,
          selectedPackSkills,
          enforceContractVersion,
        ),
        sourceDir = skill.sourceDir.toAbsolutePath().normalize(),
      )
    }
  } else {
    emptyMap()
  }
  return skillEntries + agentAddonEntries(roots)
}

private fun agentAddonEntries(roots: ReconcileSourceRoots): Map<String, ReconcileSkillEntry> =
  discoverAgentAddons(roots.repoRoot).associate { declaration ->
    "agent-addons/${declaration.slug}" to ReconcileSkillEntry(
      hash = hashAgentAddonSource(declaration.manifestPath, declaration.contentPath),
      sourceDir = declaration.addonRoot.toAbsolutePath().normalize(),
    )
  }

private fun hashAgentAddonSource(manifestPath: Path, contentPath: Path): String {
  val digest = MessageDigest.getInstance("SHA-256")
  listOf("agent-addon.yaml" to manifestPath, "content.md" to contentPath).forEach { (name, path) ->
    digest.update(name.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(Files.readAllBytes(path))
    digest.update(0)
  }
  return digest.digest().take(INSTALL_CACHE_KEY_BYTES).joinToString("") { byte -> "%02x".format(byte) }
}

private fun reconcileSkillHash(
  roots: ReconcileSourceRoots,
  skill: InstallPlanSkill,
  platformManifests: List<PlatformManifest>,
  selectedPackSkills: List<InstallPlanSkill>,
  enforceContractVersion: Boolean,
): String {
  val applicablePointers = applicablePointers(roots.repoRoot, skill.sourceDir, platformManifests)
  val supportPointers = generatedSupportPointersFor(
    repoRoot = roots.repoRoot,
    sourceSkillDir = skill.sourceDir,
    skillName = skill.name,
    skillsRoot = roots.skillsRoot,
    selectedPlatformManifests = platformManifests,
  )
  val internal = prepareInternalStaging(
    InternalStagingPreparation(
      repoRoot = roots.repoRoot,
      parentSourceDir = skill.sourceDir,
      parentSkillName = skill.name,
      skillsRoot = roots.skillsRoot,
      selectedPackSkills = selectedPackSkills,
      platformManifests = platformManifests,
      selectedPlatformManifests = platformManifests,
      parentSupportPointers = supportPointers,
      parentPointerNames = applicablePointers.map { it.second.name }.toSet(),
      enforceContractVersion = enforceContractVersion,
    ),
  )
  val authored = authoredFilesFor(
    skill.sourceDir,
    applicablePointers,
    internal.supportPointers,
    internal.sidecarNames,
  )
  val agentAddonPointers = agentAddonPointersForSkill(roots.repoRoot, skill.name)
  validateAgentAddonPointerNamespace(
    skill.name,
    authoredStagingNames(skill.sourceDir, authored) + internal.sidecarNames +
      applicablePointers.map { it.second.name } + internal.supportPointers.map { it.name } +
      listOf("SKILL.md", ".content-hash"),
    agentAddonPointers,
  )
  return computeInstallContentHash(
    InstallContentHashInputs(
      sourceSkillDir = skill.sourceDir,
      authored = authored,
      applicablePointers = applicablePointers,
      generatedSupportPointers = internal.supportPointers,
      internalChildren = internal.children,
      agentAddonPointers = agentAddonPointers,
    ),
  )
}

/**
 * Stable skill-relative key, INDEPENDENT of where the candidate tree is staged on
 * disk. Base skills key off the skills root, platform skills off the platform-packs
 * root, each under a category prefix so the same logical skill matches across the
 * upstream candidate, the local copy, and the baseline manifest regardless of their
 * absolute paths.
 */
private fun skillRelativePath(roots: ReconcileSourceRoots, skill: InstallPlanSkill): String {
  val resolvedSource = skill.sourceDir.toAbsolutePath().normalize()
  return when (skill.kind) {
    InstallPlanSkillKind.BASE -> {
      val root = roots.skillsRoot.toAbsolutePath().normalize()
      "skills/" + root.relativize(resolvedSource).toString().replace(File.separatorChar, '/')
    }
    InstallPlanSkillKind.PLATFORM_PACK -> {
      val root = roots.platformPacksRoot.toAbsolutePath().normalize()
      "platform-packs/" + root.relativize(resolvedSource).toString().replace(File.separatorChar, '/')
    }
  }
}

private fun reconcileEnumerationRequest(roots: ReconcileSourceRoots, home: Path): InstallPlanRequest =
  InstallPlanRequest(
    repoRoot = roots.repoRoot.toAbsolutePath().normalize(),
    home = home,
    agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
    platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
    telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
    mcpRegistrationChoice = McpRegistrationChoice(register = false),
    runtimeDistributionInputs = RuntimeDistributionInputs(
      runtimeInstallRoot = home.resolve(".skill-bill/runtime"),
    ),
    targetPaths = InstallationTargetPaths(
      skillsRoot = roots.skillsRoot,
      platformPacksRoot = roots.platformPacksRoot,
    ),
    windowsSymlinkPreflight = WindowsSymlinkPreflight(
      state = WindowsSymlinkPreflightState.NOT_WINDOWS,
      decision = WindowsSymlinkDecision.NOT_REQUIRED,
    ),
  )
