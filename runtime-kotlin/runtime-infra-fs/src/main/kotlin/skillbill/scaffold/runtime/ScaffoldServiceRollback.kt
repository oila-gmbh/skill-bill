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
import skillbill.error.ScaffoldRollbackError
import skillbill.install.plan.uninstallTargets
import skillbill.scaffold.policy.scaffold.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.rendering.renderContentBody
import java.nio.file.Files
import java.nio.file.Path
import skillbill.scaffold.policy.platformpack.renderPlatformPackManifestContent as renderPackManifest

internal fun rollback(txn: ScaffoldTransaction) {
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

internal fun canonicalName(payload: Map<String, Any?>, defaultName: String): String {
  val provided = payload["name"] as? String
  return when {
    provided.isNullOrBlank() -> defaultName
    provided != defaultName -> throw InvalidScaffoldPayloadError(
      "Scaffold payload field 'name' must be '$defaultName' for this scaffold kind.",
    )
    else -> provided
  }
}

internal fun optionalAddonLocationPath(payload: Map<String, Any?>, repoRoot: Path): Path? {
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

internal fun displayPath(repoRoot: Path, path: Path): String {
  val normalizedRoot = repoRoot.toAbsolutePath().normalize()
  val normalizedPath = path.toAbsolutePath().normalize()
  val display = if (normalizedPath.startsWith(normalizedRoot)) {
    normalizedRoot.relativize(normalizedPath)
  } else {
    normalizedPath
  }
  return display.toString().replace('\\', '/')
}

internal fun defaultPlatformOverrideName(platform: String, family: String): String = if (family == "quality-check") {
  "bill-$platform-code-check"
} else {
  "bill-$platform-$family"
}

// SKILL-52.1 subtask 2: `requireString`, `requireStringOrDefault`, and `requireStringList` now live
// in `skillbill.scaffold.policy` (runtime-domain) as the single source of truth. The duplicate
// private copies that used to live here were removed; callsites use the imported policy versions.

internal fun deriveDisplayName(platform: String): String = displayNameFromSlug(platform)

internal fun defaultRepoRoot(): Path = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()

internal fun renderPlatformPackManifestContent(
  plan: ScaffoldPlan,
  repoRoot: Path,
  baselineSkillPath: Path,
  qualityCheckSkillPath: Path,
): String {
  val packRoot = plan.manifestPath?.parent ?: repoRoot.resolve("platform-packs").resolve(plan.platform)
  return renderPackManifest(
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

internal fun stagePlatformPackSkills(
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
    renderContentBody(baselineContext, baselineDescription, internalFor = "bill-code-review"),
  )

  val qualityCheckContext =
    TemplateContext(plan.qualityCheckSkillName, "quality-check", plan.platform, "", plan.displayName)
  val qualityCheckDescription =
    "Use when validating ${plan.displayName} changes with the shared quality-check contract."
  stageFile(
    txn,
    qualityCheckSkillPath.resolve("content.md"),
    renderContentBody(qualityCheckContext, qualityCheckDescription, internalFor = "bill-code-check"),
  )

  plan.specialistAreas.forEach { area ->
    symlinks.addAll(stagePlatformPackArea(txn, plan, area, repoRoot))
  }
  return symlinks
}

internal fun stagePlatformPackArea(
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
    renderContentBody(areaContext, areaDescription, internalFor = "bill-code-review"),
  )
  return emptyList()
}

internal fun previewPlatformPackCreatedFiles(plan: ScaffoldPlan): List<Path> = buildList {
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

internal fun previewSubagentStubFiles(plan: ScaffoldPlan): List<Path> {
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

internal fun subagentEmissionNotes(plan: ScaffoldPlan): List<String> {
  if (!plan.shouldEmitSubagents()) {
    return emptyList()
  }
  val stubDir =
    if (plan.kind == SKILL_KIND_PLATFORM_PACK) {
      plan.baselineSkillPath ?: plan.skillPath
    } else {
      plan.skillPath
    }
  if (plan.kind == SKILL_KIND_PLATFORM_PACK && plan.bodyBasedSubagents.isEmpty()) {
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

internal fun ScaffoldPlan.shouldEmitSubagents(): Boolean = subagentSpecialists.isNotEmpty() && !subagentsSuppressed

internal fun ScaffoldPlan.isExternalAddon(): Boolean = kind == SKILL_KIND_ADD_ON && externalAddonLocationPath != null

internal fun ScaffoldPlan.externalAddonManifestPath(): Path = externalAddonLocationPath?.resolve("addon-manifest.yaml")
  ?: error("External add-on plan is missing addon_location_path.")

internal fun rollbackInstallTargets(txn: ScaffoldTransaction, errors: MutableList<String>) {
  try {
    uninstallTargets(txn.installTargets)
  } catch (error: Exception) {
    errors += "install rollback: ${error.message}"
  }
}

internal fun rollbackSymlinks(txn: ScaffoldTransaction, errors: MutableList<String>) {
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

internal fun rollbackManifests(txn: ScaffoldTransaction, errors: MutableList<String>) {
  for (snapshot in txn.manifestSnapshots.asReversed()) {
    try {
      Files.write(snapshot.manifestPath, snapshot.originalBytes)
    } catch (error: Exception) {
      errors += "manifest ${snapshot.manifestPath}: ${error.message}"
    }
  }
}

internal fun rollbackFiles(txn: ScaffoldTransaction, errors: MutableList<String>) {
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

internal fun rollbackDirs(txn: ScaffoldTransaction, errors: MutableList<String>) {
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

internal const val ADD_ON_INSTALL_NOTE: String =
  "Add-on shipped as a supporting asset of its owning platform package; auto-install does not apply."

internal fun noAgentsNote(): String =
  "No local AI agents detected; skipping auto-install. Run `./install.sh` to set up " +
    "agent paths when an agent becomes available."

internal const val PLATFORM_PACK_INSTALL_NOTE: String =
  "Auto-installed the generated platform-pack skills into detected local agents."

internal fun platformPackManifestPath(repoRoot: Path, platform: String): Path =
  repoRoot.resolve("platform-packs").resolve(platform).resolve("platform.yaml")
