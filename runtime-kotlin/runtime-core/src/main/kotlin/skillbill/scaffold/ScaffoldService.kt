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

package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.MissingPlatformPackError
import skillbill.error.MissingSupportingFileTargetError
import skillbill.error.ScaffoldPayloadVersionMismatchError
import skillbill.error.ScaffoldRollbackError
import skillbill.error.SkillAlreadyExistsError
import skillbill.error.UnknownPreShellFamilyError
import skillbill.error.UnknownSkillKindError
import skillbill.install.detectAgents
import skillbill.install.installSkill
import skillbill.install.model.InstallTransaction
import skillbill.install.uninstallTargets
import skillbill.scaffold.model.ScaffoldResult
import java.nio.file.Files
import java.nio.file.Path

private const val SKILL_KIND_HORIZONTAL = "horizontal"
private const val SKILL_KIND_PLATFORM_OVERRIDE_PILOTED = "platform-override-piloted"
private const val SKILL_KIND_PLATFORM_PACK = "platform-pack"
private const val SKILL_KIND_CODE_REVIEW_AREA = "code-review-area"
private const val SKILL_KIND_ADD_ON = "add-on"

private val SUPPORTED_SKILL_KINDS =
  setOf(
    SKILL_KIND_HORIZONTAL,
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED,
    SKILL_KIND_PLATFORM_PACK,
    SKILL_KIND_CODE_REVIEW_AREA,
    SKILL_KIND_ADD_ON,
  )

private val SHELLED_FAMILIES = setOf("code-review", "quality-check")
private val PRE_SHELL_FAMILIES = setOf("feature-implement", "feature-verify")

private val PLATFORM_PACK_PRESETS: Map<String, PlatformPackPreset> =
  mapOf(
    "java" to PlatformPackPreset(
      displayName = "Java",
      strongSignals = listOf("pom.xml", "build.gradle", "src/main/java"),
      tieBreakers = listOf("Prefer Java when Maven metadata or Java source markers dominate generic JVM signals."),
    ),
    "php" to PlatformPackPreset(
      displayName = "PHP",
      strongSignals = listOf("composer.json", ".php", "phpunit.xml"),
      tieBreakers = listOf("Prefer PHP when Composer metadata or .php source files dominate mixed backend signals."),
    ),
  )

private data class PlatformPackPreset(
  val displayName: String,
  val strongSignals: List<String>,
  val tieBreakers: List<String>,
)

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

private data class ScaffoldPlan(
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
)

private data class ScaffoldExecutionResult(
  val createdFiles: List<Path>,
  val manifestEdits: List<Path>,
  val symlinks: List<Path>,
  val installTargets: List<Path>,
  val notes: List<String>,
)

fun scaffold(payload: Map<String, Any?>, dryRun: Boolean = false): ScaffoldResult {
  require(payload.isNotEmpty()) {
    "Scaffold payload must be a JSON object mapping string keys to values."
  }

  validatePayloadVersion(payload)
  val kind = detectKind(payload)
  val repoRoot = resolveRepoRoot(payload)
  val plan = planScaffold(payload, repoRoot, kind)
  return if (dryRun) {
    renderDryRunResult(plan, repoRoot)
  } else {
    runScaffold(plan, repoRoot)
  }
}

private fun renderDryRunResult(plan: ScaffoldPlan, repoRoot: Path): ScaffoldResult = ScaffoldResult(
  kind = plan.kind,
  skillName = plan.skillName,
  skillPath = plan.skillPath,
  createdFiles = previewCreatedFiles(plan),
  manifestEdits = previewManifestEdits(plan, repoRoot),
  symlinks = previewSymlinks(plan, repoRoot),
  installTargets = emptyList(),
  notes = plan.notes + listOf("Dry run - no filesystem changes applied."),
)

private fun runScaffold(plan: ScaffoldPlan, repoRoot: Path): ScaffoldResult {
  val txn = ScaffoldTransaction()
  return try {
    val execution = executeScaffold(txn, plan, repoRoot)
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

private fun executeScaffold(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): ScaffoldExecutionResult {
  val execution =
    when (plan.kind) {
      SKILL_KIND_PLATFORM_PACK -> createPlatformPack(txn, plan, repoRoot)
      else -> stageSingleScaffold(txn, plan, repoRoot)
    }
  validateScaffold(plan, repoRoot)
  val (installTargets, installNotes) = performInstall(txn, plan)
  return execution.copy(installTargets = installTargets, notes = execution.notes + installNotes)
}

private fun validatePayloadVersion(payload: Map<String, Any?>) {
  val version = payload["scaffold_payload_version"] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload is missing required field 'scaffold_payload_version'.",
    )
  if (version != SCAFFOLD_PAYLOAD_VERSION) {
    throw ScaffoldPayloadVersionMismatchError(
      "Scaffold payload declares 'scaffold_payload_version' '$version' " +
        "but the scaffolder expects '$SCAFFOLD_PAYLOAD_VERSION'.",
    )
  }
}

private fun detectKind(payload: Map<String, Any?>): String {
  val kind = payload["kind"] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'kind' must be a non-empty string.",
    )
  if (kind !in SUPPORTED_SKILL_KINDS) {
    throw UnknownSkillKindError(
      "Scaffold payload declares unsupported kind '$kind'. " +
        "Supported kinds: $SUPPORTED_SKILL_KINDS.",
    )
  }
  return kind
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

private fun planScaffold(payload: Map<String, Any?>, repoRoot: Path, kind: String): ScaffoldPlan = when (kind) {
  SKILL_KIND_HORIZONTAL -> planHorizontal(payload, repoRoot)
  SKILL_KIND_PLATFORM_OVERRIDE_PILOTED -> planPlatformOverridePiloted(payload, repoRoot)
  SKILL_KIND_PLATFORM_PACK -> planPlatformPack(payload, repoRoot)
  SKILL_KIND_CODE_REVIEW_AREA -> planCodeReviewArea(payload, repoRoot)
  SKILL_KIND_ADD_ON -> planAddOn(payload, repoRoot)
  else -> throw UnknownSkillKindError("Scaffold payload declares unsupported kind '$kind'.")
}

private fun planHorizontal(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val name = requireString(payload, "name")
  val skillPath = repoRoot.resolve("skills").resolve(name)
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
    contentBody = payload["content_body"] as? String,
  )
}

private fun planPlatformOverridePiloted(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  val family = requireString(payload, "family")
  val name = canonicalName(payload, defaultName = "bill-$platform-$family")
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
    "Keep scaffold-managed `SKILL.md` wrappers and `shell-ceremony.md` " +
    "unchanged unless you are intentionally changing the shared contract."
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
  )
}

private fun planPlatformPack(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val platform = requireString(payload, "platform")
  val defaults = resolvePlatformPackDefaults(payload, platform)
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  if (Files.exists(packRoot)) {
    throw SkillAlreadyExistsError(
      "Platform pack target '$packRoot' already exists. " +
        "Remove it or pick a new platform slug before retrying.",
    )
  }
  val baselineName = canonicalName(payload, defaultName = "bill-$platform-code-review")
  val qualityCheckName = "bill-$platform-quality-check"
  val selection = resolvePlatformPackSelection(payload)
  val selectedAreas = selection.selectedAreas
  val specialistNames =
    selectedAreas.associateWith { area -> "bill-$platform-code-review-$area" }
  val specialistPaths =
    selectedAreas.associateWith { area ->
      packRoot.resolve("code-review").resolve(specialistNames.getValue(area))
    }
  val specialistMetadata =
    selectedAreas.associateWith { area -> defaultAreaFocus(area) }
  val notes = platformPackNotes(platform, defaults.presetUsed, selectedAreas)

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
    installPaths = buildPlatformPackInstallPaths(
      packRoot = packRoot,
      baselineName = baselineName,
      qualityCheckName = qualityCheckName,
      specialistPaths = specialistPaths,
      selectedAreas = selectedAreas,
    ),
    contentBody = payload["content_body"] as? String,
  )
}

private data class PlatformPackSelection(
  val selectedAreas: List<String>,
)

private fun resolvePlatformPackSelection(payload: Map<String, Any?>): PlatformPackSelection {
  val skeletonMode = requireStringOrDefault(payload, "skeleton_mode", "full")
  val specialistAreas = payload["specialist_areas"]?.let {
    requireStringList(it, "specialist_areas").also { areas ->
      val unknown = areas.filter { area -> area !in APPROVED_CODE_REVIEW_AREAS }
      if (unknown.isNotEmpty()) {
        throw InvalidScaffoldPayloadError(
          "Scaffold payload field 'specialist_areas' contains unknown areas $unknown; " +
            "approved areas: $APPROVED_CODE_REVIEW_AREAS.",
        )
      }
    }
  }
  if (skeletonMode !in setOf("starter", "full")) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'skeleton_mode' must be one of [full, starter] when provided.",
    )
  }
  if (specialistAreas != null && payload.containsKey("skeleton_mode")) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload may not provide both 'skeleton_mode' and 'specialist_areas'; " +
        "choose one specialist selection mode.",
    )
  }
  val selectedAreas =
    specialistAreas
      ?: if (skeletonMode == "full") APPROVED_CODE_REVIEW_AREAS.sorted() else emptyList()
  return PlatformPackSelection(selectedAreas = selectedAreas)
}

private fun platformPackNotes(platform: String, presetUsed: Boolean, selectedAreas: List<String>): List<String> {
  val notes = mutableListOf<String>()
  if (presetUsed) {
    notes +=
      "Applied built-in platform preset for '$platform'. " +
      "Override 'routing_signals' only when the defaults need adjustment."
  }
  notes += if (selectedAreas.isNotEmpty()) {
    "Full skeleton scaffolded with ${selectedAreas.size} approved code-review area stubs."
  } else {
    "Quality-check scaffolded by default."
  }
  notes += "Follow-on code-review-area scaffolds can extend the pack without manual manifest edits."
  notes += sharedContractNote()
  return notes
}

private fun planCodeReviewArea(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
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

private fun planAddOn(payload: Map<String, Any?>, repoRoot: Path): ScaffoldPlan {
  val name = requireString(payload, "name")
  val platform = requireString(payload, "platform")
  val packRoot = repoRoot.resolve("platform-packs").resolve(platform)
  if (!Files.isRegularFile(packRoot.resolve("platform.yaml"))) {
    throw MissingPlatformPackError(
      "Platform pack '$platform' does not exist at '$packRoot'. " +
        "Create a conforming platform.yaml before adding a governed add-on into it.",
    )
  }
  val skillFile = packRoot.resolve("addons").resolve("$name.md")
  return ScaffoldPlan(
    kind = SKILL_KIND_ADD_ON,
    skillName = name,
    skillPath = packRoot.resolve("addons"),
    skillFile = skillFile,
    contentFile = null,
    family = "add-on",
    platform = platform,
    area = "",
    isShelled = false,
    notes = emptyList(),
    description = requireStringOrDefault(payload, "description", ""),
    addonBody = payload["body"] as? String,
  )
}

private data class PlatformPackDefaults(
  val displayName: String,
  val strongSignals: List<String>,
  val tieBreakers: List<String>,
  val presetUsed: Boolean,
)

private fun resolvePlatformPackDefaults(payload: Map<String, Any?>, platform: String): PlatformPackDefaults {
  val preset = PLATFORM_PACK_PRESETS[platform]
  val routing = payload["routing_signals"] as? Map<*, *>
  val strong = routing?.get("strong")?.let { requireStringList(it, "routing_signals.strong") }
  val tieBreakers = routing?.get("tie_breakers")?.let { requireStringList(it, "routing_signals.tie_breakers") }
  val resolvedStrong = strong ?: preset?.strongSignals.orEmpty()
  val resolvedTieBreakers = tieBreakers ?: preset?.tieBreakers.orEmpty()
  if (resolvedStrong.isEmpty()) {
    if (preset == null) {
      throw InvalidScaffoldPayloadError(
        "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal " +
          "when no built-in platform preset exists for '$platform'.",
      )
    }
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'routing_signals.strong' must contain at least one routing signal.",
    )
  }
  val displayName = requireStringOrDefault(payload, "display_name", preset?.displayName ?: deriveDisplayName(platform))
  return PlatformPackDefaults(
    displayName = displayName,
    strongSignals = resolvedStrong,
    tieBreakers = resolvedTieBreakers,
    presetUsed = preset != null && routing == null,
  )
}

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
    stageFile(txn, plan.skillFile, renderSkillWrapper(plan))
    plan.contentFile?.let { content ->
      stageFile(txn, content, renderContentSheet(plan))
    }
  }
  val manifestEdits = applyManifestEdits(txn, plan, repoRoot)
  val symlinks = stageSidecarSymlinks(txn, plan, repoRoot)
  return ScaffoldExecutionResult(
    createdFiles = txn.createdPaths.toList(),
    manifestEdits = manifestEdits,
    symlinks = symlinks,
    installTargets = emptyList(),
    notes = emptyList(),
  )
}

private fun renderSkillWrapper(plan: ScaffoldPlan): String =
  renderSkillBody(skillContext(plan), plan.description, areaFocus(plan))

private fun renderContentSheet(plan: ScaffoldPlan): String = renderContentBody(
  skillContext(plan),
  description = plan.description,
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

private fun snapshotManifest(txn: ScaffoldTransaction, manifestPath: Path) {
  txn.manifestSnapshots += ManifestSnapshot(manifestPath, Files.readAllBytes(manifestPath))
}

private fun applyManifestEdits(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): List<Path> {
  return when (plan.kind) {
    SKILL_KIND_CODE_REVIEW_AREA -> {
      val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
      snapshotManifest(txn, manifestPath)
      val declaredAreaPath = repoRoot.relativize(plan.skillFile).toString().replace('\\', '/')
      appendCodeReviewArea(manifestPath, plan.area, declaredAreaPath, defaultAreaFocus(plan.area))
      listOf(manifestPath)
    }
    SKILL_KIND_PLATFORM_OVERRIDE_PILOTED -> {
      if (plan.isShelled && plan.family == "quality-check") {
        val manifestPath = repoRoot.resolve("platform-packs").resolve(plan.platform).resolve("platform.yaml")
        snapshotManifest(txn, manifestPath)
        val declaredPath = repoRoot.relativize(plan.skillFile).toString().replace('\\', '/')
        setDeclaredQualityCheckFile(manifestPath, declaredPath)
        listOf(manifestPath)
      } else {
        emptyList()
      }
    }
    else -> emptyList()
  }
}

private fun stageSidecarSymlinks(txn: ScaffoldTransaction, plan: ScaffoldPlan, repoRoot: Path): List<Path> =
  stageSidecarSymlinksForSkill(txn, plan.skillName, plan.skillPath, repoRoot)

private fun stageSidecarSymlinksForSkill(
  txn: ScaffoldTransaction,
  skillName: String,
  skillPath: Path,
  repoRoot: Path,
): List<Path> {
  val required = requiredSupportingFilesForSkill(skillName)
  if (required.isEmpty()) {
    return emptyList()
  }
  val targets = supportingFileTargets(repoRoot)
  val created = mutableListOf<Path>()
  for (fileName in required) {
    val target = targets[fileName] ?: throw missingSupportingFileTargetError(fileName, skillName)
    val linkPath = skillPath.resolve(fileName)
    if (Files.isSymbolicLink(linkPath) || Files.exists(linkPath)) {
      continue
    }
    Files.createSymbolicLink(linkPath, target)
    txn.createdSymlinks.add(linkPath)
    created.add(linkPath)
  }
  return created
}

private fun previewCreatedFiles(plan: ScaffoldPlan): List<Path> = when (plan.kind) {
  SKILL_KIND_PLATFORM_PACK -> previewPlatformPackCreatedFiles(plan)
  SKILL_KIND_ADD_ON -> listOf(plan.skillFile)
  else -> buildList {
    add(plan.skillFile)
    plan.contentFile?.let(::add)
  }
}

private fun previewManifestEdits(plan: ScaffoldPlan, repoRoot: Path): List<Path> = when (plan.kind) {
  SKILL_KIND_CODE_REVIEW_AREA, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED ->
    listOf(platformPackManifestPath(repoRoot, plan.platform))
  else -> emptyList()
}

private fun previewSymlinks(plan: ScaffoldPlan, repoRoot: Path): List<Path> {
  val preview = mutableListOf<Path>()
  when (plan.kind) {
    SKILL_KIND_PLATFORM_PACK -> {
      val baselineSkillPath = plan.baselineSkillPath ?: return emptyList()
      val qualityCheckSkillPath = plan.qualityCheckSkillPath ?: return emptyList()
      preview += previewSidecars(plan.baselineSkillName, baselineSkillPath, repoRoot)
      preview += previewSidecars(plan.qualityCheckSkillName, qualityCheckSkillPath, repoRoot)
      plan.specialistAreas.forEach { area ->
        preview += previewSidecars(
          plan.specialistSkillNames.getValue(area),
          plan.specialistSkillPaths.getValue(area),
          repoRoot,
        )
      }
    }
    SKILL_KIND_HORIZONTAL, SKILL_KIND_PLATFORM_OVERRIDE_PILOTED, SKILL_KIND_CODE_REVIEW_AREA ->
      preview += previewSidecars(plan.skillName, plan.skillPath, repoRoot)
    else -> {}
  }
  return preview
}

private fun previewSidecars(skillName: String, skillPath: Path, repoRoot: Path): List<Path> {
  val required = requiredSupportingFilesForSkill(skillName)
  if (required.isEmpty()) return emptyList()
  val targets = supportingFileTargets(repoRoot)
  required.forEach { fileName ->
    if (fileName !in targets) {
      throw missingSupportingFileTargetError(fileName, skillName)
    }
  }
  return required.map { skillPath.resolve(it) }
}

private fun validateScaffold(plan: ScaffoldPlan, repoRoot: Path) {
  if (plan.kind == SKILL_KIND_ADD_ON || plan.kind == SKILL_KIND_HORIZONTAL) {
    return
  }
  loadPlatformPack(repoRoot.resolve("platform-packs").resolve(plan.platform))
}

private fun performInstall(txn: ScaffoldTransaction, plan: ScaffoldPlan): Pair<List<Path>, List<String>> {
  val agents = detectAgents()
  val installTx = InstallTransaction()
  val installPaths = when (plan.kind) {
    SKILL_KIND_ADD_ON -> emptyList()
    SKILL_KIND_PLATFORM_PACK -> plan.installPaths
    else -> listOf(plan.skillPath)
  }
  val targets =
    installPaths.flatMap { installPath ->
      installSkill(installPath, agents, transaction = installTx)
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

private fun requireString(payload: Map<String, Any?>, key: String): String =
  (payload[key] as? String)?.takeIf { it.isNotBlank() }
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a non-empty string.",
    )

private fun requireStringOrDefault(payload: Map<String, Any?>, key: String, default: String): String =
  (payload[key] as? String)?.takeIf { it.isNotBlank() } ?: default

private fun requireStringList(value: Any?, fieldName: String): List<String> {
  if (value !is List<*>) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must be a list of strings.",
    )
  }
  return value.map {
    it as? String ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$fieldName' must contain only non-empty strings.",
    )
  }.also {
    if (it.any(String::isBlank)) {
      throw InvalidScaffoldPayloadError(
        "Scaffold payload field '$fieldName' must contain only non-empty strings.",
      )
    }
  }
}

private fun deriveDisplayName(platform: String): String = platform.split('-').joinToString(" ") { part ->
  part.replaceFirstChar {
    if (it.isLowerCase()) it.titlecase() else it.toString()
  }
}

private fun defaultRepoRoot(): Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

private fun buildPlatformPackInstallPaths(
  packRoot: Path,
  baselineName: String,
  qualityCheckName: String,
  specialistPaths: Map<String, Path>,
  selectedAreas: List<String>,
): List<Path> = buildList {
  add(packRoot.resolve("code-review").resolve(baselineName))
  add(packRoot.resolve("quality-check").resolve(qualityCheckName))
  selectedAreas.forEach { area ->
    add(specialistPaths.getValue(area))
  }
}

private fun renderPlatformPackManifestContent(
  plan: ScaffoldPlan,
  repoRoot: Path,
  baselineSkillPath: Path,
  qualityCheckSkillPath: Path,
): String = renderPlatformPackManifest(
  platform = plan.platform,
  displayName = plan.displayName,
  strongSignals = plan.routingSignals,
  tieBreakers = plan.tieBreakers,
  declaredCodeReviewAreas = plan.specialistAreas,
  baselineContentPath = repoRoot.relativize(baselineSkillPath.resolve("SKILL.md"))
    .toString()
    .replace('\\', '/'),
  declaredAreaFiles = plan.specialistSkillPaths.mapValues { (_, path) ->
    repoRoot.relativize(path.resolve("SKILL.md")).toString().replace('\\', '/')
  },
  declaredQualityCheckFile = repoRoot.relativize(qualityCheckSkillPath.resolve("SKILL.md"))
    .toString()
    .replace('\\', '/'),
  areaMetadata = plan.specialistAreaMetadata,
)

private fun stagePlatformPackSkills(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
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
    baselineSkillPath.resolve("SKILL.md"),
    renderSkillBody(baselineContext, baselineDescription, ""),
  )
  stageFile(
    txn,
    baselineSkillPath.resolve("content.md"),
    renderContentBody(baselineContext, baselineDescription),
  )
  symlinks += stageSidecarSymlinksForSkill(txn, plan.baselineSkillName, baselineSkillPath, repoRoot)

  val qualityCheckContext =
    TemplateContext(plan.qualityCheckSkillName, "quality-check", plan.platform, "", plan.displayName)
  val qualityCheckDescription =
    "Use when validating ${plan.displayName} changes with the shared quality-check contract."
  stageFile(
    txn,
    qualityCheckSkillPath.resolve("SKILL.md"),
    renderSkillBody(qualityCheckContext, qualityCheckDescription, ""),
  )
  stageFile(
    txn,
    qualityCheckSkillPath.resolve("content.md"),
    renderContentBody(qualityCheckContext, qualityCheckDescription),
  )
  symlinks.addAll(stageSidecarSymlinksForSkill(txn, plan.qualityCheckSkillName, qualityCheckSkillPath, repoRoot))

  plan.specialistAreas.forEach { area ->
    symlinks.addAll(stagePlatformPackArea(txn, plan, area, repoRoot))
  }
  return symlinks
}

private fun stagePlatformPackArea(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  area: String,
  repoRoot: Path,
): List<Path> {
  val areaPath = plan.specialistSkillPaths.getValue(area)
  val areaName = plan.specialistSkillNames.getValue(area)
  val areaContext = TemplateContext(areaName, "code-review", plan.platform, area, plan.displayName)
  val areaDescription = "Use when reviewing ${plan.displayName} changes for $area risks."
  stageFile(txn, areaPath.resolve("SKILL.md"), renderSkillBody(areaContext, areaDescription, defaultAreaFocus(area)))
  stageFile(txn, areaPath.resolve("content.md"), renderContentBody(areaContext, areaDescription))
  return stageSidecarSymlinksForSkill(txn, areaName, areaPath, repoRoot)
}

private fun previewPlatformPackCreatedFiles(plan: ScaffoldPlan): List<Path> = buildList {
  plan.manifestPath?.let(::add)
  plan.baselineSkillPath?.let {
    add(it.resolve("SKILL.md"))
    add(it.resolve("content.md"))
  }
  plan.qualityCheckSkillPath?.let {
    add(it.resolve("SKILL.md"))
    add(it.resolve("content.md"))
  }
  plan.specialistSkillPaths.values.forEach { path ->
    add(path.resolve("SKILL.md"))
    add(path.resolve("content.md"))
  }
}

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

private fun missingSupportingFileTargetError(fileName: String, skillName: String): MissingSupportingFileTargetError =
  MissingSupportingFileTargetError(
    "Runtime supporting file '$fileName' is not registered for '$skillName'.",
  )

private fun sharedContractNote(): String = "Author skill instructions only in sibling `content.md` files. " +
  "Keep scaffold-managed `SKILL.md` wrappers and `shell-ceremony.md` unchanged unless " +
  "you are intentionally changing the shared contract."

private const val ADD_ON_INSTALL_NOTE: String =
  "Add-on shipped as a supporting asset of its owning platform package; auto-install does not apply."

private fun noAgentsNote(): String =
  "No local AI agents detected; skipping auto-install. Run `./install.sh` to set up " +
    "agent paths when an agent becomes available."

private const val PLATFORM_PACK_INSTALL_NOTE: String =
  "Auto-installed the generated platform-pack skills into detected local agents."

private fun platformPackManifestPath(repoRoot: Path, platform: String): Path =
  repoRoot.resolve("platform-packs").resolve(platform).resolve("platform.yaml")
