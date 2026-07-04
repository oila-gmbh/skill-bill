@file:Suppress(
  "TooManyFunctions",
  "LongMethod",
  "ComplexMethod",
  "NestedBlockDepth",
  "ReturnCount",
  "ThrowsCount",
  "TooGenericExceptionCaught",
  "MaxLineLength",
)

package skillbill.scaffold.runtime

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.MissingPlatformPackError
import skillbill.error.ScaffoldRollbackError
import skillbill.error.SkillAlreadyExistsError
import skillbill.error.UnknownPreShellFamilyError
import skillbill.error.UnknownSkillKindError
import skillbill.install.model.InstallTransaction
import skillbill.install.plan.InstallContext
import skillbill.install.plan.detectAgents
import skillbill.install.plan.installSkill
import skillbill.install.plan.uninstallTargets
import skillbill.scaffold.manifest.appendCodeReviewArea
import skillbill.scaffold.manifest.appendExternalAddonManifestRegistration
import skillbill.scaffold.manifest.appendGovernedAddonManifestRegistration
import skillbill.scaffold.manifest.appendReadmeCatalogRow
import skillbill.scaffold.manifest.renderExternalAddonManifestRegistration
import skillbill.scaffold.manifest.renderGovernedAddonManifestRegistration
import skillbill.scaffold.manifest.renderReadmeCatalogRow
import skillbill.scaffold.manifest.setDeclaredQualityCheckFile
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.SKILL_KIND_CODE_REVIEW_AREA
import skillbill.scaffold.policy.SKILL_KIND_HORIZONTAL
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_OVERRIDE_PILOTED
import skillbill.scaffold.policy.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.policy.sharedContractNote
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.rendering.inferSkillDescription
import skillbill.scaffold.rendering.renderAddonBody
import skillbill.scaffold.rendering.renderContentBody
import skillbill.scaffold.rendering.renderNativeAgentBundleStubs
import java.nio.file.Files
import java.nio.file.Path
import skillbill.scaffold.payload.detectKind as policyDetectKind
import skillbill.scaffold.payload.optionalSpecialistSubagents as policyOptionalSpecialistSubagents
import skillbill.scaffold.payload.rejectBaselineLayersForNonPlatformPack as policyRejectBaselineLayersForNonPlatformPack
import skillbill.scaffold.payload.rejectLeafSubagentSpecialists as policyRejectLeafSubagentSpecialists
import skillbill.scaffold.payload.requireStringMap as requireString
import skillbill.scaffold.payload.requireStringOrDefaultMap as requireStringOrDefault
import skillbill.scaffold.payload.resolvePlatformPackDefaults as policyResolvePlatformPackDefaults
import skillbill.scaffold.payload.resolvePlatformPackSelection as policyResolvePlatformPackSelection
import skillbill.scaffold.payload.validatePayloadVersion as policyValidatePayloadVersion
import skillbill.scaffold.policy.buildPlatformPackInstallPaths as policyBuildPlatformPackInstallPaths
import skillbill.scaffold.policy.platformPackNotes as policyPlatformPackNotes
import skillbill.scaffold.policy.renderPlatformPackManifestContent as policyRenderPlatformPackManifestContent

// SKILL-52.1 subtask 2: skill-kind discriminators, supported-kinds set, orchestrator-kind set,
// subagent name pattern, and built-in platform-pack preset descriptors all live in
// `skillbill.scaffold.policy` (runtime-domain) as the single source of truth. This file imports
// them above. The wizard-facing family taxonomy (`SHELLED_FAMILIES`, `PRE_SHELL_FAMILIES`) and
// slug->displayName projection (`PLATFORM_PACK_PRESETS`) remain in `ScaffoldSupport.kt` while
// the desktop wizard catalog delegation lives there.
//
// SKILL-52.1 subtask 3 (AC1): IO-coupled validators that previously lived as top-level
// functions inside this file (`validateBaselineLayerPayloadReferences`, `validateScaffold`,
// `plannedAuthoringTarget`, `resolveAddonConsumerSkillDirs`, `validateAddonConsumerSkillDir`,
// `optionalBaselineLayers`) now live on the capability-aligned adapters
// `FileSystemScaffoldRepoValidation` and `FileSystemScaffoldSourceLoader` under
// `skillbill.infrastructure.fs`. The orchestrator calls into these adapter instances; the
// `ImplementationOwnershipArchitectureTest` asserts the function FQN resolves to those
// adapter classes, not to top-level `skillbill.scaffold`.
//
// SKILL-52.1 subtask 3 (F-001): the previous file-static `private val scaffoldRepoValidation`
// / `scaffoldSourceLoader` singletons coexisted with the DI-bound port instances at runtime
// (two adapter instances per graph). They are removed. The orchestrator entrypoint
// `scaffoldWithAdapters(...)` now receives the two adapter instances as explicit parameters;
// `FileSystemScaffoldOrchestrator` (DI-bound) is the only public caller and threads its
// kotlin-inject-provided adapters through. This file deliberately does NOT import or
// reference the `FileSystemScaffoldRepoValidation` / `FileSystemScaffoldSourceLoader`
// concrete class names; the parameter types below carry that dependency through the
// orchestrator instead.

private data class ManifestSnapshot(
  val manifestPath: Path,
  val originalBytes: ByteArray,
)

private data class ScaffoldTransaction(
  val createdPaths: MutableList<Path> = mutableListOf(),
  val createdDirs: MutableList<Path> = mutableListOf(),
  val createdSymlinks: MutableList<Path> = mutableListOf(),
  val manifestSnapshots: MutableList<ManifestSnapshot> = mutableListOf(),
  val installTargets: MutableList<Path> = mutableListOf(),
)

internal data class ScaffoldPlan(
  val kind: String,
  val skillName: String,
  val skillPath: Path,
  val skillFile: Path,
  val contentFile: Path?,
  val family: String,
  val platform: String,
  val area: String,
  val isShelled: Boolean,
  val notes: List<String>,
  val displayName: String = "",
  val description: String = "",
  val manifestPath: Path? = null,
  val routingSignals: List<String> = emptyList(),
  val tieBreakers: List<String> = emptyList(),
  val specialistAreas: List<String> = emptyList(),
  val specialistAreaMetadata: Map<String, String> = emptyMap(),
  val specialistSkillNames: Map<String, String> = emptyMap(),
  val specialistSkillPaths: Map<String, Path> = emptyMap(),
  val baselineSkillName: String = "",
  val baselineSkillPath: Path? = null,
  val qualityCheckSkillName: String = "",
  val qualityCheckSkillPath: Path? = null,
  val installPaths: List<Path> = emptyList(),
  val createdFiles: List<Path> = emptyList(),
  val contentBody: String? = null,
  val addonBody: String? = null,
  val addonConsumerSkillDirs: List<String> = emptyList(),
  val externalAddonLocationPath: Path? = null,
  val baselineLayers: List<CodeReviewBaselineLayer> = emptyList(),
  val subagentSpecialists: List<String> = emptyList(),
  val subagentDescriptions: Map<String, String> = emptyMap(),
  val subagentsSuppressed: Boolean = false,
)

private data class ScaffoldExecutionResult(
  val createdFiles: List<Path>,
  val manifestEdits: List<Path>,
  val symlinks: List<Path>,
  val installTargets: List<Path>,
  val notes: List<String>,
)

/**
 * SKILL-52.1 subtask 3 (F-001): orchestrator entrypoint. Receives the two carved IO
 * validator adapters as explicit parameters so the DI-bound `FileSystemScaffoldOrchestrator`
 * can thread its kotlin-inject-provided singletons through; this eliminates the prior
 * file-static parallel instances. The adapters are passed as opaque seams via the
 * [ScaffoldAdapterSeams] holder so this file does not need to import the concrete adapter
 * class names directly.
 */
internal fun scaffoldWithAdapters(
  payload: Map<String, Any?>,
  dryRun: Boolean,
  adapters: ScaffoldAdapterSeams,
): ScaffoldResult {
  require(payload.isNotEmpty()) {
    "Scaffold payload must be a JSON object mapping string keys to values."
  }

  policyValidatePayloadVersion(payload)
  val kind = policyDetectKind(payload)
  val repoRoot = resolveRepoRoot(payload)
  val plan = planScaffold(payload, repoRoot, kind, adapters)
  return if (dryRun) {
    renderDryRunResult(plan, repoRoot)
  } else {
    runScaffold(plan, repoRoot, adapters)
  }
}

/**
 * Port-style adapter facades that decouple the orchestrator file from the concrete adapter
 * class names. `FileSystemScaffoldOrchestrator` (in `skillbill.infrastructure.fs`) binds
 * kotlin-inject-provided adapters into instances of this holder and threads them through
 * `scaffoldWithAdapters`. Keeping this seam local to `runtime-infra-fs` preserves the F-006
 * constraint that the carved validators remain `internal fun` on the adapter classes.
 */
internal data class ScaffoldAdapterSeams(
  val validateScaffold: (ScaffoldPlan, Path) -> Unit,
  val optionalBaselineLayers: (Map<String, Any?>, Path, String) -> List<CodeReviewBaselineLayer>,
  val resolveAddonConsumerSkillDirs: (
    Map<String, Any?>,
    Path,
    skillbill.scaffold.model.PlatformManifest,
  ) -> List<String>,
)

private fun renderDryRunResult(plan: ScaffoldPlan, repoRoot: Path): ScaffoldResult = ScaffoldResult(
  kind = plan.kind,
  skillName = plan.skillName,
  skillPath = plan.skillPath,
  createdFiles = previewCreatedFiles(plan),
  manifestEdits = previewManifestEdits(plan, repoRoot),
  manifestPreviews = previewManifestPreviews(plan, repoRoot),
  symlinks = emptyList(),
  installTargets = emptyList(),
  notes = plan.notes + listOf("Dry run - no filesystem changes applied."),
)

private fun runScaffold(plan: ScaffoldPlan, repoRoot: Path, adapters: ScaffoldAdapterSeams): ScaffoldResult {
  val txn = ScaffoldTransaction()
  return try {
    val execution = executeScaffold(txn, plan, repoRoot, adapters)
    ScaffoldResult(
      kind = plan.kind,
      skillName = plan.skillName,
      skillPath = plan.skillPath,
      createdFiles = execution.createdFiles,
      manifestEdits = execution.manifestEdits,
      symlinks = execution.symlinks,
      installTargets = execution.installTargets,
      notes = plan.notes + execution.notes,
    )
  } catch (error: Throwable) {
    rollback(txn)
    throw error
  }
}

private fun executeScaffold(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
): ScaffoldExecutionResult {
  val execution =
    when (plan.kind) {
      SKILL_KIND_PLATFORM_PACK -> createPlatformPack(txn, plan, repoRoot)
      else -> stageSingleScaffold(txn, plan, repoRoot)
    }
  adapters.validateScaffold(plan, repoRoot)
  val (installTargets, installNotes) = performInstall(txn, plan, repoRoot)
  return execution.copy(
    installTargets = installTargets,
    notes = execution.notes + installNotes + subagentEmissionNotes(plan),
  )
}

private fun resolveRepoRoot(payload: Map<String, Any?>): Path {
  val repoRootRaw = payload["repo_root"] as? String ?: return defaultRepoRoot()
  if (repoRootRaw.isBlank()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'repo_root' must be a non-empty string when provided.",
    )
  }
  return Path.of(repoRootRaw).toAbsolutePath().normalize()
}

private fun planScaffold(
  payload: Map<String, Any?>,
  repoRoot: Path,
  kind: String,
  adapters: ScaffoldAdapterSeams,
): ScaffoldPlan = when (kind) {
  SKILL_KIND_HORIZONTAL -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planHorizontal(payload, repoRoot)
  }
  SKILL_KIND_PLATFORM_OVERRIDE_PILOTED -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planPlatformOverridePiloted(payload, repoRoot)
  }
  SKILL_KIND_PLATFORM_PACK -> planPlatformPack(payload, repoRoot, adapters)
  SKILL_KIND_CODE_REVIEW_AREA -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planCodeReviewArea(payload, repoRoot)
  }
  SKILL_KIND_ADD_ON -> {
    policyRejectBaselineLayersForNonPlatformPack(payload, kind)
    planAddOn(payload, repoRoot, adapters)
  }
  else -> throw UnknownSkillKindError("Scaffold payload declares unsupported kind '$kind'.")
}

private fun planHorizontal(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val name = requireString(payload, "name")
  val skillPath = repoRoot.resolve("skills").resolve(name)
  val subagents = policyOptionalSpecialistSubagents(payload, SKILL_KIND_HORIZONTAL)
  return ScaffoldPlan(
    kind = SKILL_KIND_HORIZONTAL,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = "horizontal",
    platform = "",
    area = "",
    isShelled = false,
    notes = emptyList(),
    description = requireStringOrDefault(payload, "description", ""),
    contentBody = payload["content_body"] as? String,
    subagentSpecialists = subagents.specialists,
    subagentsSuppressed = subagents.suppressed,
  )
}

private fun planPlatformOverridePiloted(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  val family = requireString(payload, "family")
  val name = canonicalName(payload, defaultName = defaultPlatformOverrideName(platform, family))
  val subagents = policyOptionalSpecialistSubagents(payload, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED)
  val isShelled = family in SHELLED_FAMILIES
  val notes = mutableListOf<String>()
  if (!isShelled) {
    if (family !in PRE_SHELL_FAMILIES) {
      throw UnknownPreShellFamilyError(
        "Scaffold payload declares pre-shell family '$family' " +
          "that is not in the registered set $PRE_SHELL_FAMILIES.",
      )
    }
    val skillPath = repoRoot.resolve("skills").resolve(platform).resolve(name)
    notes +=
      "Pre-shell family '$family' placed at '${repoRoot.relativize(skillPath)}'; " +
      "will move when the family is piloted onto the shell+content contract."
    return ScaffoldPlan(
      kind = SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
      skillName = name,
      skillPath = skillPath,
      skillFile = skillPath.resolve("SKILL.md"),
      contentFile = skillPath.resolve("content.md"),
      family = family,
      platform = platform,
      area = "",
      isShelled = false,
      notes = notes,
      contentBody = payload["content_body"] as? String,
      subagentSpecialists = subagents.specialists,
      subagentsSuppressed = subagents.suppressed,
    )
  }

  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  val manifestPath = packRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw MissingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a skill into it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val skillPath = packRoot.resolve(family).resolve(name)
  notes +=
    "Author skill instructions only in sibling `content.md` files. " +
    "Generated `SKILL.md` wrappers and platform pointer files are render/install output."
  return ScaffoldPlan(
    kind = SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = family,
    platform = platform,
    area = "",
    isShelled = true,
    notes = notes,
    displayName = pack.displayName ?: deriveDisplayName(platform),
    contentBody = payload["content_body"] as? String,
    subagentSpecialists = subagents.specialists,
    subagentsSuppressed = subagents.suppressed,
  )
}

private fun planPlatformPack(
  payload: Map<String, Any?>,
  repoRoot: Path,
  adapters: ScaffoldAdapterSeams,
): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  val subagents = policyOptionalSpecialistSubagents(payload, SKILL_KIND_PLATFORM_PACK)
  val defaults = policyResolvePlatformPackDefaults(payload, platform)
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  if (Files.exists(packRoot)) {
    throw SkillAlreadyExistsError(
      "Platform pack target '$packRoot' already exists. " +
        "Remove it or pick a new platform slug before retrying.",
    )
  }
  val baselineName = canonicalName(payload, defaultName = "bill-$platform-code-review")
  val qualityCheckName = "bill-$platform-code-check"
  val baselineLayers = adapters.optionalBaselineLayers(payload, repoRoot, platform)
  val selection = policyResolvePlatformPackSelection(payload)
  val selectedAreas = selection.selectedAreas
  val specialistNames =
    selectedAreas.associateWith { area -> "bill-$platform-code-review-$area" }
  val specialistPaths =
    selectedAreas.associateWith { area ->
      packRoot.resolve("code-review").resolve(specialistNames.getValue(area))
    }
  val specialistMetadata =
    selectedAreas.associateWith { area -> defaultAreaFocus(area) }
  val platformPackSubagents = when {
    subagents.suppressed -> emptyList()
    subagents.specialists.isNotEmpty() -> subagents.specialists
    else -> selectedAreas.map { area -> specialistNames.getValue(area) }
  }
  val platformPackSubagentDescriptions =
    if (!subagents.suppressed && subagents.specialists.isEmpty()) {
      selectedAreas.associate { area ->
        val name = specialistNames.getValue(area)
        val description = inferSkillDescription(
          TemplateContext(name, "code-review", platform, area, defaults.displayName),
          defaultAreaFocus(area),
        )
        name to description
      }
    } else {
      emptyMap()
    }
  val notes = policyPlatformPackNotes(platform, defaults.presetUsed, selectedAreas)

  return ScaffoldPlan(
    kind = SKILL_KIND_PLATFORM_PACK,
    skillName = baselineName,
    skillPath = packRoot,
    skillFile = packRoot.resolve("code-review").resolve(baselineName).resolve("SKILL.md"),
    contentFile = packRoot.resolve("code-review").resolve(baselineName).resolve("content.md"),
    family = "code-review",
    platform = platform,
    area = "",
    isShelled = true,
    notes = notes,
    displayName = defaults.displayName,
    description = requireStringOrDefault(payload, "description", ""),
    manifestPath = packRoot.resolve("platform.yaml"),
    routingSignals = defaults.strongSignals,
    tieBreakers = defaults.tieBreakers,
    specialistAreas = selectedAreas,
    specialistAreaMetadata = specialistMetadata,
    specialistSkillNames = specialistNames,
    specialistSkillPaths = specialistPaths,
    baselineSkillName = baselineName,
    baselineSkillPath = packRoot.resolve("code-review").resolve(baselineName),
    qualityCheckSkillName = qualityCheckName,
    qualityCheckSkillPath = packRoot.resolve("quality-check").resolve(qualityCheckName),
    installPaths = policyBuildPlatformPackInstallPaths(
      packRoot = packRoot,
      baselineName = baselineName,
      qualityCheckName = qualityCheckName,
      specialistPaths = specialistPaths,
      selectedAreas = selectedAreas,
    ),
    contentBody = payload["content_body"] as? String,
    baselineLayers = baselineLayers,
    subagentSpecialists = platformPackSubagents,
    subagentDescriptions = platformPackSubagentDescriptions,
    subagentsSuppressed = subagents.suppressed,
  )
}

// SKILL-52.1 subtask 3 (AC1): `optionalBaselineLayers`, `validateBaselineLayerPayloadReferences`,
// and `validateBaselineLayerModeSupport` now live on `FileSystemScaffoldRepoValidation`.
// Callsites below delegate to `scaffoldRepoValidation.optionalBaselineLayers(...)`.

private fun planCodeReviewArea(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  policyRejectLeafSubagentSpecialists(payload, SKILL_KIND_CODE_REVIEW_AREA)
  val platform = requireString(payload, "platform")
  val area = requireString(payload, "area")
  if (area !in APPROVED_CODE_REVIEW_AREAS) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload declares code-review area '$area' that is not in the approved set $APPROVED_CODE_REVIEW_AREAS.",
    )
  }
  val name = canonicalName(payload, defaultName = "bill-$platform-code-review-$area")
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  val manifestPath = packRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw MissingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a code-review area to it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val skillPath = packRoot.resolve("code-review").resolve(name)
  return ScaffoldPlan(
    kind = SKILL_KIND_CODE_REVIEW_AREA,
    skillName = name,
    skillPath = skillPath,
    skillFile = skillPath.resolve("SKILL.md"),
    contentFile = skillPath.resolve("content.md"),
    family = "code-review",
    platform = platform,
    area = area,
    isShelled = true,
    notes = listOf(sharedContractNote()),
    displayName = pack.displayName ?: deriveDisplayName(platform),
    description = requireStringOrDefault(payload, "description", ""),
    contentBody = payload["content_body"] as? String,
  )
}

private fun planAddOn(payload: Map<String, Any?>, repoRoot: Path, adapters: ScaffoldAdapterSeams): ScaffoldPlan {
  policyRejectLeafSubagentSpecialists(payload, SKILL_KIND_ADD_ON)
  val name = requireString(payload, "name")
  val platform = requireString(payload, "platform")
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  if (!Files.isRegularFile(packRoot.resolve("platform.yaml"))) {
    throw MissingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a governed add-on into it.",
    )
  }
  val pack = loadPlatformPack(packRoot)
  val externalLocationPath = optionalAddonLocationPath(payload, repoRoot)
  val addonDir = externalLocationPath ?: packRoot.resolve("addons")
  val skillFile = addonDir.resolve("$name.md")
  val addOnFile = displayPath(repoRoot, skillFile)
  return ScaffoldPlan(
    kind = SKILL_KIND_ADD_ON,
    skillName = name,
    skillPath = addonDir,
    skillFile = skillFile,
    contentFile = null,
    family = "add-on",
    platform = platform,
    area = "",
    isShelled = false,
    notes = listOf("After creation, edit the generated add-on body in `$addOnFile`, then validate and render."),
    description = requireStringOrDefault(payload, "description", ""),
    addonBody = payload["body"] as? String,
    addonConsumerSkillDirs = adapters.resolveAddonConsumerSkillDirs(payload, packRoot, pack),
    externalAddonLocationPath = externalLocationPath,
  )
}

// SKILL-52.1 subtask 3 (AC1): `resolveAddonConsumerSkillDirs` and `validateAddonConsumerSkillDir`
// now live on `FileSystemScaffoldSourceLoader`. Callsites delegate to
// `scaffoldSourceLoader.resolveAddonConsumerSkillDirs(...)`.

private fun createPlatformPack(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): ScaffoldExecutionResult {
  val manifestPath = plan.manifestPath ?: error("Platform pack plan missing manifest path.")
  val baselineSkillPath = plan.baselineSkillPath ?: error("Platform pack plan missing baseline skill path.")
  val qualityCheckSkillPath =
    plan.qualityCheckSkillPath ?: error("Platform pack plan missing quality-check skill path.")
  stageFile(
    txn,
    manifestPath,
    renderPlatformPackManifestContent(
      plan,
      repoRoot,
      baselineSkillPath,
      qualityCheckSkillPath,
    ),
  )
  val symlinks = stagePlatformPackSkills(txn, plan, repoRoot, baselineSkillPath, qualityCheckSkillPath)
  if (plan.shouldEmitSubagents()) {
    stageSubagentStubs(
      txn,
      orchestratorSkillPath = baselineSkillPath,
      specialists = plan.subagentSpecialists,
      descriptions = plan.subagentDescriptions,
    )
  }
  return ScaffoldExecutionResult(
    createdFiles = txn.createdPaths.toList(),
    manifestEdits = listOf(manifestPath),
    symlinks = symlinks,
    installTargets = emptyList(),
    notes = emptyList(),
  )
}

private fun stageSingleScaffold(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): ScaffoldExecutionResult {
  if (plan.kind == SKILL_KIND_ADD_ON) {
    stageFile(txn, plan.skillFile, renderAddonBody(plan.skillName, plan.description, plan.addonBody))
  } else {
    plan.contentFile?.let { content ->
      val contentText = if (plan.kind == SKILL_KIND_CODE_REVIEW_AREA || plan.isShelled) {
        renderDeclaredPackContentSheet(plan)
      } else {
        renderContentSheet(plan)
      }
      stageFile(txn, content, contentText)
    }
  }
  val manifestEdits = applyManifestEdits(txn, plan, repoRoot)
  if (plan.shouldEmitSubagents()) {
    stageSubagentStubs(
      txn,
      orchestratorSkillPath = plan.skillPath,
      specialists = plan.subagentSpecialists,
      descriptions = plan.subagentDescriptions,
    )
  }
  return ScaffoldExecutionResult(
    createdFiles = txn.createdPaths.toList(),
    manifestEdits = manifestEdits,
    symlinks = emptyList(),
    installTargets = emptyList(),
    notes = emptyList(),
  )
}

private fun renderContentSheet(plan: ScaffoldPlan): String = renderContentBody(
  skillContext(plan),
  description = effectiveDescription(plan),
  contentBody = plan.contentBody,
)

private fun renderDeclaredPackContentSheet(plan: ScaffoldPlan): String = renderContentBody(
  skillContext(plan),
  description = effectiveDescription(plan),
  contentBody = plan.contentBody,
)

private fun skillContext(plan: ScaffoldPlan): TemplateContext = TemplateContext(
  skillName = plan.skillName,
  family = plan.family,
  platform = plan.platform,
  area = plan.area,
  displayName = plan.displayName.ifBlank { deriveDisplayName(plan.platform) },
)

private fun areaFocus(plan: ScaffoldPlan): String =
  plan.area.takeIf { it.isNotBlank() }?.let(::defaultAreaFocus).orEmpty()

private fun effectiveDescription(plan: ScaffoldPlan): String {
  val context = skillContext(plan)
  return plan.description.ifBlank { inferSkillDescription(context, areaFocus(plan)) }
}

private fun stageFile(txn: ScaffoldTransaction, path: Path, content: String) {
  if (Files.exists(path)) {
    throw SkillAlreadyExistsError(
      "Skill target '$path' already exists. Remove it or pick a new name before retrying.",
    )
  }
  val parents = mutableListOf<Path>()
  var cursor = path.parent
  while (cursor != null && !Files.exists(cursor)) {
    parents.add(cursor)
    cursor = cursor.parent
  }
  parents.asReversed().forEach { dir ->
    Files.createDirectories(dir)
    txn.createdDirs.add(dir)
  }
  Files.writeString(path, content)
  txn.createdPaths.add(path)
}

private fun stageSubagentStubs(
  txn: ScaffoldTransaction,
  orchestratorSkillPath: Path,
  specialists: List<String>,
  descriptions: Map<String, String>,
): List<Path> {
  val sourcePath = orchestratorSkillPath.resolve("native-agents").resolve("agents.yaml")
  stageFile(txn, sourcePath, renderNativeAgentBundleStubs(specialists, descriptions))
  return listOf(sourcePath)
}

private fun snapshotManifest(txn: ScaffoldTransaction, manifestPath: Path) {
  txn.manifestSnapshots += ManifestSnapshot(manifestPath, Files.readAllBytes(manifestPath))
}

private fun applyManifestEdits(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): List<Path> {
  return when (plan.kind) {
    SKILL_KIND_HORIZONTAL -> {
      val readmePath = repoRoot.resolve("README.md")
      if (!Files.exists(readmePath)) {
        emptyList()
      } else {
        snapshotManifest(txn, readmePath)
        appendReadmeCatalogRow(readmePath, plan.skillName, effectiveDescription(plan))
        listOf(readmePath)
      }
    }
    SKILL_KIND_CODE_REVIEW_AREA -> {
      val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
      snapshotManifest(txn, manifestPath)
      val declaredAreaPath = manifestPath.parent.relativize(
        plan.contentFile ?: plan.skillPath.resolve("content.md"),
      ).toString().replace('\\', '/')
      appendCodeReviewArea(manifestPath, plan.area, declaredAreaPath, defaultAreaFocus(plan.area))
      listOf(manifestPath)
    }
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED -> {
      if (plan.isShelled && plan.family == "quality-check") {
        val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
        snapshotManifest(txn, manifestPath)
        val declaredPath = manifestPath.parent.relativize(
          plan.contentFile ?: plan.skillPath.resolve("content.md"),
        ).toString().replace('\\', '/')
        setDeclaredQualityCheckFile(manifestPath, declaredPath)
        listOf(manifestPath)
      } else {
        emptyList()
      }
    }
    SKILL_KIND_ADD_ON -> {
      if (plan.addonConsumerSkillDirs.isEmpty()) {
        emptyList()
      } else if (plan.isExternalAddon()) {
        val manifestPath = plan.externalAddonManifestPath()
        if (Files.exists(manifestPath)) {
          snapshotManifest(txn, manifestPath)
          appendExternalAddonManifestRegistration(
            manifestPath = manifestPath,
            skillRelativeDirs = plan.addonConsumerSkillDirs,
            addonSlug = plan.skillName,
          )
        } else {
          stageFile(
            txn,
            manifestPath,
            renderExternalAddonManifestRegistration("", plan.addonConsumerSkillDirs, plan.skillName),
          )
        }
        listOf(manifestPath)
      } else {
        val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
        snapshotManifest(txn, manifestPath)
        appendGovernedAddonManifestRegistration(
          manifestPath = manifestPath,
          platform = plan.platform,
          skillRelativeDirs = plan.addonConsumerSkillDirs,
          addonSlug = plan.skillName,
        )
        listOf(manifestPath)
      }
    }
    else -> emptyList()
  }
}

private fun previewCreatedFiles(plan: ScaffoldPlan): List<Path> = when (plan.kind) {
  SKILL_KIND_PLATFORM_PACK -> previewPlatformPackCreatedFiles(plan) + previewSubagentStubFiles(plan)
  SKILL_KIND_ADD_ON -> if (plan.isExternalAddon() && !Files.exists(plan.externalAddonManifestPath())) {
    listOf(plan.skillFile, plan.externalAddonManifestPath())
  } else {
    listOf(plan.skillFile)
  }
  else -> buildList {
    plan.contentFile?.let(::add)
    addAll(previewSubagentStubFiles(plan))
  }
}

private fun previewManifestEdits(plan: ScaffoldPlan, repoRoot: Path): List<Path> = when (plan.kind) {
  SKILL_KIND_PLATFORM_PACK -> listOf(plan.manifestPath ?: platformPackManifestPath(repoRoot, plan.platform))
  SKILL_KIND_ADD_ON -> if (plan.addonConsumerSkillDirs.isEmpty()) {
    emptyList()
  } else if (plan.isExternalAddon()) {
    listOf(plan.externalAddonManifestPath())
  } else {
    listOf(platformPackManifestPath(repoRoot, plan.platform))
  }
  SKILL_KIND_CODE_REVIEW_AREA, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED ->
    listOf(platformPackManifestPath(repoRoot, plan.platform))
  SKILL_KIND_HORIZONTAL -> {
    val readmePath = repoRoot.resolve("README.md")
    if (Files.exists(readmePath)) listOf(readmePath) else emptyList()
  }
  else -> emptyList()
}

private fun previewManifestPreviews(plan: ScaffoldPlan, repoRoot: Path): Map<Path, String> = when (plan.kind) {
  SKILL_KIND_PLATFORM_PACK -> {
    val manifestPath = plan.manifestPath ?: platformPackManifestPath(repoRoot, plan.platform)
    val baselineSkillPath = plan.baselineSkillPath ?: error("Platform pack plan missing baseline skill path.")
    val qualityCheckSkillPath =
      plan.qualityCheckSkillPath ?: error("Platform pack plan missing quality-check skill path.")
    mapOf(manifestPath to renderPlatformPackManifestContent(plan, repoRoot, baselineSkillPath, qualityCheckSkillPath))
  }
  SKILL_KIND_ADD_ON -> {
    if (plan.addonConsumerSkillDirs.isEmpty()) {
      emptyMap()
    } else if (plan.isExternalAddon()) {
      val manifestPath = plan.externalAddonManifestPath()
      val current = if (Files.exists(manifestPath)) Files.readString(manifestPath) else ""
      mapOf(
        manifestPath to renderExternalAddonManifestRegistration(
          text = current,
          skillRelativeDirs = plan.addonConsumerSkillDirs,
          addonSlug = plan.skillName,
        ),
      )
    } else {
      val manifestPath = platformPackManifestPath(repoRoot, plan.platform)
      mapOf(
        manifestPath to renderGovernedAddonManifestRegistration(
          text = Files.readString(manifestPath),
          platform = plan.platform,
          skillRelativeDirs = plan.addonConsumerSkillDirs,
          addonSlug = plan.skillName,
        ),
      )
    }
  }
  SKILL_KIND_HORIZONTAL -> {
    val readmePath = repoRoot.resolve("README.md")
    if (Files.exists(readmePath)) {
      mapOf(
        readmePath to renderReadmeCatalogRow(
          text = Files.readString(readmePath),
          skillName = plan.skillName,
          description = effectiveDescription(plan),
        ),
      )
    } else {
      emptyMap()
    }
  }
  else -> emptyMap()
}

// SKILL-52.1 subtask 3 (AC1): `validateScaffold` and `plannedAuthoringTarget` now live on
// `FileSystemScaffoldRepoValidation`. Callsites delegate to
// `scaffoldRepoValidation.validateScaffold(...)`.

private fun performInstall(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
): Pair<List<Path>, List<String>> {
  val agents = detectAgents()
  val installTx = InstallTransaction()
  val installPaths = when (plan.kind) {
    SKILL_KIND_ADD_ON -> emptyList()
    SKILL_KIND_PLATFORM_PACK -> plan.installPaths
    else -> listOf(plan.skillPath)
  }
  // F-015: hoist platform-pack manifest discovery out of the per-skill loop. Walking
  // `platform-packs` once is O(packs); doing it per skill in a multi-skill platform-pack scaffold
  // is O(packs * skills). Pass the pre-resolved list down through installSkill so applicablePointers
  // reuses it.
  val packsRoot = repoRoot.resolve("platform-packs")
  val manifests = if (Files.isDirectory(packsRoot)) discoverPlatformPackManifests(packsRoot) else emptyList()
  val context = InstallContext(repoRoot = repoRoot, manifests = manifests)
  val targets =
    installPaths.flatMap { installPath ->
      installSkill(installPath, agents, transaction = installTx, context = context)
    }
  txn.installTargets += targets
  val notes = when {
    plan.kind == SKILL_KIND_ADD_ON -> listOf(
      ADD_ON_INSTALL_NOTE,
    )
    agents.isEmpty() -> listOf(
      noAgentsNote(),
    )
    plan.kind == SKILL_KIND_PLATFORM_PACK -> listOf(PLATFORM_PACK_INSTALL_NOTE)
    else -> emptyList()
  }
  return targets to notes
}

private fun rollback(txn: ScaffoldTransaction) {
  val errors = mutableListOf<String>()
  rollbackInstallTargets(txn, errors)
  rollbackSymlinks(txn, errors)
  rollbackManifests(txn, errors)
  rollbackFiles(txn, errors)
  rollbackDirs(txn, errors)
  if (errors.isNotEmpty()) {
    throw ScaffoldRollbackError(
      "Rollback encountered errors while reverting scaffold: ${errors.joinToString("; ")}",
    )
  }
}

private fun canonicalName(payload: Map<String, Any?>, defaultName: String): String {
  val provided = payload["name"] as? String
  return when {
    provided.isNullOrBlank() -> defaultName
    provided != defaultName -> throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'name' must be '$defaultName' for this scaffold kind.",
    )
    else -> provided
  }
}

private fun optionalAddonLocationPath(payload: Map<String, Any?>, repoRoot: Path): Path? {
  if (!payload.containsKey("addon_location_path")) return null
  val rawPath = payload["addon_location_path"] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'addon_location_path' must be a non-empty string when provided.",
    )
  if (rawPath.isBlank()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'addon_location_path' must be a non-empty string when provided.",
    )
  }
  val expanded = when {
    rawPath == "~" -> System.getProperty("user.home")
    rawPath.startsWith("~/") -> Path.of(System.getProperty("user.home"))
      .resolve(rawPath.removePrefix("~/"))
      .toString()
    else -> rawPath
  }
  val candidate = Path.of(expanded)
  return if (candidate.isAbsolute) {
    candidate.normalize()
  } else {
    repoRoot.resolve(candidate).normalize()
  }
}

private fun displayPath(repoRoot: Path, path: Path): String {
  val normalizedRoot = repoRoot.toAbsolutePath().normalize()
  val normalizedPath = path.toAbsolutePath().normalize()
  val display = if (normalizedPath.startsWith(normalizedRoot)) {
    normalizedRoot.relativize(normalizedPath)
  } else {
    normalizedPath
  }
  return display.toString().replace('\\', '/')
}

private fun defaultPlatformOverrideName(platform: String, family: String): String = if (family == "quality-check") {
  "bill-$platform-code-check"
} else {
  "bill-$platform-$family"
}

// SKILL-52.1 subtask 2: `requireString`, `requireStringOrDefault`, and `requireStringList` now live
// in `skillbill.scaffold.policy` (runtime-domain) as the single source of truth. The duplicate
// private copies that used to live here were removed; callsites use the imported policy versions.

private fun deriveDisplayName(platform: String): String = displayNameFromSlug(platform)

private fun defaultRepoRoot(): Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

private fun renderPlatformPackManifestContent(
  plan: ScaffoldPlan,
  repoRoot: Path,
  baselineSkillPath: Path,
  qualityCheckSkillPath: Path,
): String {
  val packRoot = plan.manifestPath?.parent ?: repoRoot.resolve("platform-packs").resolve(plan.platform)
  return policyRenderPlatformPackManifestContent(
    platform = plan.platform,
    displayName = plan.displayName,
    routingSignals = plan.routingSignals,
    tieBreakers = plan.tieBreakers,
    specialistAreas = plan.specialistAreas,
    specialistAreaMetadata = plan.specialistAreaMetadata,
    baselineLayers = plan.baselineLayers,
    packRoot = packRoot,
    baselineSkillPath = baselineSkillPath,
    qualityCheckSkillPath = qualityCheckSkillPath,
    specialistSkillPaths = plan.specialistSkillPaths,
  )
}

private fun stagePlatformPackSkills(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  @Suppress("UNUSED_PARAMETER") repoRoot: Path,
  baselineSkillPath: Path,
  qualityCheckSkillPath: Path,
): List<Path> {
  val symlinks = mutableListOf<Path>()
  val baselineContext =
    TemplateContext(plan.baselineSkillName, "code-review", plan.platform, "", plan.displayName)
  val baselineDescription =
    if (plan.description.isNotBlank()) {
      plan.description
    } else {
      "Use when reviewing changes in ${plan.displayName} codebases."
    }
  stageFile(
    txn,
    baselineSkillPath.resolve("content.md"),
    renderContentBody(baselineContext, baselineDescription),
  )

  val qualityCheckContext =
    TemplateContext(plan.qualityCheckSkillName, "quality-check", plan.platform, "", plan.displayName)
  val qualityCheckDescription =
    "Use when validating ${plan.displayName} changes with the shared quality-check contract."
  stageFile(
    txn,
    qualityCheckSkillPath.resolve("content.md"),
    renderContentBody(qualityCheckContext, qualityCheckDescription),
  )

  plan.specialistAreas.forEach { area ->
    symlinks.addAll(stagePlatformPackArea(txn, plan, area, repoRoot))
  }
  return symlinks
}

private fun stagePlatformPackArea(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  area: String,
  @Suppress("UNUSED_PARAMETER") repoRoot: Path,
): List<Path> {
  val areaPath = plan.specialistSkillPaths.getValue(area)
  val areaName = plan.specialistSkillNames.getValue(area)
  val areaContext = TemplateContext(areaName, "code-review", plan.platform, area, plan.displayName)
  val areaDescription = "Use when reviewing ${plan.displayName} changes for $area risks."
  stageFile(
    txn,
    areaPath.resolve("content.md"),
    renderContentBody(areaContext, areaDescription),
  )
  return emptyList()
}

private fun previewPlatformPackCreatedFiles(plan: ScaffoldPlan): List<Path> = buildList {
  plan.manifestPath?.let(::add)
  plan.baselineSkillPath?.let {
    add(it.resolve("content.md"))
  }
  plan.qualityCheckSkillPath?.let {
    add(it.resolve("content.md"))
  }
  plan.specialistSkillPaths.values.forEach { path ->
    add(path.resolve("content.md"))
  }
}

private fun previewSubagentStubFiles(plan: ScaffoldPlan): List<Path> {
  if (!plan.shouldEmitSubagents()) {
    return emptyList()
  }
  val stubDir =
    if (plan.kind == SKILL_KIND_PLATFORM_PACK) {
      plan.baselineSkillPath ?: return emptyList()
    } else {
      plan.skillPath
    }
  return listOf(stubDir.resolve("native-agents").resolve("agents.yaml"))
}

private fun subagentEmissionNotes(plan: ScaffoldPlan): List<String> {
  if (!plan.shouldEmitSubagents()) {
    return emptyList()
  }
  val stubDir =
    if (plan.kind == SKILL_KIND_PLATFORM_PACK) {
      plan.baselineSkillPath ?: plan.skillPath
    } else {
      plan.skillPath
    }
  if (plan.kind == SKILL_KIND_PLATFORM_PACK && plan.subagentDescriptions.isNotEmpty()) {
    return listOf(
      "Subagent bundle emitted: ${plan.subagentSpecialists.size} entries. " +
        "Native agents compose from the generated code-review content.md files; " +
        "fill in those content.md files before shipping.",
    )
  }
  return listOf(
    "Subagent bundle emitted: ${plan.subagentSpecialists.size} entries. " +
      "Fill in the TODO placeholders in $stubDir/native-agents/agents.yaml before shipping; " +
      "install renders provider artifacts.",
  )
}

private fun ScaffoldPlan.shouldEmitSubagents(): Boolean = subagentSpecialists.isNotEmpty() && !subagentsSuppressed

private fun ScaffoldPlan.isExternalAddon(): Boolean = kind == SKILL_KIND_ADD_ON && externalAddonLocationPath != null

private fun ScaffoldPlan.externalAddonManifestPath(): Path = externalAddonLocationPath?.resolve("addon-manifest.yaml")
  ?: error("External add-on plan is missing addon_location_path.")

private fun rollbackInstallTargets(txn: ScaffoldTransaction, errors: MutableList<String>) {
  try {
    uninstallTargets(txn.installTargets)
  } catch (error: Exception) {
    errors += "install rollback: ${error.message}"
  }
}

private fun rollbackSymlinks(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (link in txn.createdSymlinks.asReversed()) {
    try {
      if (Files.isSymbolicLink(link) || Files.exists(link)) {
        Files.deleteIfExists(link)
      }
    } catch (error: Exception) {
      errors += "symlink $link: ${error.message}"
    }
  }
}

private fun rollbackManifests(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (snapshot in txn.manifestSnapshots.asReversed()) {
    try {
      Files.write(snapshot.manifestPath, snapshot.originalBytes)
    } catch (error: Exception) {
      errors += "manifest ${snapshot.manifestPath}: ${error.message}"
    }
  }
}

private fun rollbackFiles(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (path in txn.createdPaths.asReversed()) {
    try {
      if (Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
        Files.deleteIfExists(path)
      }
    } catch (error: Exception) {
      errors.add("file $path: ${error.message}")
    }
  }
}

private fun rollbackDirs(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (directory in txn.createdDirs.asReversed()) {
    try {
      if (Files.isDirectory(directory) && Files.list(directory).use { !it.findAny().isPresent }) {
        Files.deleteIfExists(directory)
      }
    } catch (error: Exception) {
      errors.add("dir $directory: ${error.message}")
    }
  }
}

// SKILL-52.1 subtask 2: `sharedContractNote` is owned by `skillbill.scaffold.policy` (runtime-domain).
// The duplicate private definition that used to live here was removed; callsites import the policy
// version (see the top-of-file imports).

private const val ADD_ON_INSTALL_NOTE: String =
  "Add-on shipped as a supporting asset of its owning platform package; auto-install does not apply."

private fun noAgentsNote(): String =
  "No local AI agents detected; skipping auto-install. Run `./install.sh` to set up " +
    "agent paths when an agent becomes available."

private const val PLATFORM_PACK_INSTALL_NOTE: String =
  "Auto-installed the generated platform-pack skills into detected local agents."

private fun platformPackManifestPath(repoRoot: Path, platform: String): Path =
  repoRoot.resolve("platform-packs").resolve(platform).resolve("platform.yaml")
