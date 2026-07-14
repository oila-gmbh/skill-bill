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
 * against its repo root), three hashes are computed:
 *  - UPSTREAM (the candidate/clone source root),
 *  - LOCAL    (the copied `~/.skill-bill` source root),
 *  - BASELINE (the manifest's last-copied-in hash).
 *
 * Classification:
 *  - no baseline entry, no/identical local     -> new-upstream
 *  - no baseline entry, divergent local copy   -> conflict (migration window: WARN + prompt,
 *                                                 never silently overwrite the local edit)
 *  - no upstream counterpart (local only)      -> locally-authored (preserve, report)
 *  - local == baseline && upstream != baseline -> adopt (take upstream, refresh baseline)
 *  - local != baseline && upstream == baseline -> keep-local
 *  - local != baseline && upstream != baseline -> conflict (WARN + prompt)
 *  - local == baseline && upstream == baseline -> no-op (idempotent keep-local with no churn)
 *
 * Idempotent: identical upstream/local/baseline inputs yield only keep-local/no-op
 * outcomes, an empty conflicts list, and no baseline change.
 */
internal data class ReconcileSourceRoots(
  val repoRoot: Path,
  val skillsRoot: Path,
  val platformPacksRoot: Path,
)

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
)

internal fun computeReconciliationPlan(
  upstream: ReconcileSourceRoots,
  local: ReconcileSourceRoots,
  home: Path,
  baseline: BaselineManifest,
): ReconciliationPlan {
  val upstreamSkills = enumerateSkills(upstream, home)
  val localSkills = enumerateSkills(local, home)
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
  // No upstream counterpart: the skill was authored locally; preserve + report.
  if (upstreamHash == null) {
    if (localHash == null) {
      // Defensive: a path appears in neither source set. Loud-fail rather than emit a bogus outcome.
      throw ReconciliationConflictError(
        skillRelativePath = skillRelativePath,
        reason = "skill is present in neither the upstream nor the local source tree.",
      )
    }
    return SkillReconciliationOutcome.LocallyAuthored(
      skillRelativePath = skillRelativePath,
      localHash = localHash,
      baselineHash = baselineHash,
    )
  }

  // No baseline entry: first install, a newly-shipped upstream skill, or the migration
  // window. Delegated so a divergent local copy is never silently overwritten.
  if (baselineHash == null) {
    return classifyNoBaseline(skillRelativePath, upstreamHash, localHash)
  }

  if (localHash == null) {
    return SkillReconciliationOutcome.Adopt(
      skillRelativePath = skillRelativePath,
      upstreamHash = upstreamHash,
      localHash = baselineHash,
      baselineHash = baselineHash,
    )
  }

  val effectiveLocalHash = localHash
  val localMatchesBaseline = effectiveLocalHash == baselineHash
  val upstreamMatchesBaseline = upstreamHash == baselineHash

  return when {
    localMatchesBaseline && upstreamMatchesBaseline ->
      // No-op: nothing changed on either side. Modelled as keep-local so no baseline churn.
      SkillReconciliationOutcome.KeepLocal(
        skillRelativePath = skillRelativePath,
        upstreamHash = upstreamHash,
        localHash = effectiveLocalHash,
        baselineHash = baselineHash,
      )
    localMatchesBaseline && !upstreamMatchesBaseline ->
      SkillReconciliationOutcome.Adopt(
        skillRelativePath = skillRelativePath,
        upstreamHash = upstreamHash,
        localHash = effectiveLocalHash,
        baselineHash = baselineHash,
      )
    !localMatchesBaseline && upstreamMatchesBaseline ->
      SkillReconciliationOutcome.KeepLocal(
        skillRelativePath = skillRelativePath,
        upstreamHash = upstreamHash,
        localHash = effectiveLocalHash,
        baselineHash = baselineHash,
      )
    else ->
      SkillReconciliationOutcome.Conflict(
        skillRelativePath = skillRelativePath,
        upstreamHash = upstreamHash,
        localHash = effectiveLocalHash,
        baselineHash = baselineHash,
      )
  }
}

/**
 * Classify a skill that has NO baseline entry (first install, newly-shipped upstream, or
 * the migration window where an existing user has a populated local copy but no manifest
 * yet). When the local copy is absent or already byte-identical to upstream there is no
 * edit to lose -> new-upstream (no prompt). When a local copy exists AND diverges from
 * upstream we cannot prove the difference is safe to overwrite, so classify it a CONFLICT
 * (WARN + prompt; no-TTY aborts) rather than silently clobbering the local edit. The
 * conflict's baseline hash is synthesized to the upstream hash so an accepted apply
 * baselines to upstream (there is no real prior baseline to carry).
 */
private fun classifyNoBaseline(
  skillRelativePath: String,
  upstreamHash: String,
  localHash: String?,
): SkillReconciliationOutcome {
  if (localHash == null || localHash == upstreamHash) {
    return SkillReconciliationOutcome.NewUpstream(
      skillRelativePath = skillRelativePath,
      upstreamHash = upstreamHash,
      localHash = localHash,
    )
  }
  return SkillReconciliationOutcome.Conflict(
    skillRelativePath = skillRelativePath,
    upstreamHash = upstreamHash,
    localHash = localHash,
    baselineHash = upstreamHash,
  )
}

/**
 * Enumerate every skill under [roots] and map its skill-relative path -> ([content
 * hash] + on-disk skill dir). Returns an empty map when a source root is absent (e.g. a
 * fresh install with no copied `~/.skill-bill` source yet) so reconciliation classifies
 * upstream skills as new-upstream rather than failing. The skill dir is carried so the
 * APPLY can replace the live dir from the upstream dir without rebuilding paths.
 */
internal fun enumerateSkills(roots: ReconcileSourceRoots, home: Path): Map<String, ReconcileSkillEntry> {
  val skillEntries = if (Files.isDirectory(roots.skillsRoot)) {
    val request = reconcileEnumerationRequest(roots, home)
    val platformManifests = discoverPlatformManifests(roots.platformPacksRoot)
    // Reuse the approved builder seam for skill enumeration so this policy never
    // references the domain InstallPlanPolicy directly (adapter-ownership rule).
    val skills = enumerateInstallPlanSkills(request)
    val selectedPackSkills = skills.filter { candidate ->
      candidate.kind == InstallPlanSkillKind.PLATFORM_PACK && candidate.internalFor != null
    }
    skills.associate { skill ->
      skillRelativePath(roots, skill) to ReconcileSkillEntry(
        hash = reconcileSkillHash(roots, skill, platformManifests, selectedPackSkills),
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
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun reconcileSkillHash(
  roots: ReconcileSourceRoots,
  skill: InstallPlanSkill,
  platformManifests: List<PlatformManifest>,
  selectedPackSkills: List<InstallPlanSkill>,
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
