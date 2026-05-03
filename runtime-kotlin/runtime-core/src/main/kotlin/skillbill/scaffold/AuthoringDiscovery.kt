package skillbill.scaffold

import skillbill.error.SkillBillRuntimeException
import skillbill.scaffold.model.PlatformManifest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private const val PRE_SHELL_PLATFORM_PATH_PARTS = 3

internal fun selectedTargets(repoRoot: Path, skillNames: List<String>): List<AuthoringTarget> {
  val targets = discoverTargets(repoRoot)
  if (skillNames.isEmpty()) {
    return targets.values.sortedBy { target -> target.skillName }
  }
  val missing = skillNames.toSet() - targets.keys
  if (missing.isNotEmpty()) {
    throw SkillBillRuntimeException("Unknown content-managed skill(s): ${missing.sorted().joinToString(", ")}.")
  }
  return skillNames.map { skillName -> targets.getValue(skillName) }
}

internal fun <T> runWithUpgradeRollback(originalBytes: Map<Path, ByteArray>, block: () -> T): T = try {
  block()
} catch (error: SkillBillRuntimeException) {
  restoreFiles(originalBytes)
  throw error
} catch (error: IOException) {
  restoreFiles(originalBytes)
  throw error
} catch (error: IllegalArgumentException) {
  restoreFiles(originalBytes)
  throw error
}

internal fun resolveTarget(repoRoot: Path, skillName: String): AuthoringTarget = discoverTargets(repoRoot)[skillName]
  ?: throw SkillBillRuntimeException(
    "Skill '$skillName' is not a content-managed skill with a sibling content.md file.",
  )

internal fun discoverTargets(repoRoot: Path): Map<String, AuthoringTarget> {
  val discovered = linkedMapOf<String, AuthoringTarget>()
  discoverPlatformPackManifests(repoRoot.resolve("platform-packs")).forEach { pack ->
    recordPackTargets(discovered, pack)
  }

  val skillsRoot = repoRoot.resolve("skills")
  if (!Files.isDirectory(skillsRoot)) {
    return discovered
  }
  Files.walk(skillsRoot).use { stream ->
    stream
      .filter { path -> path.name == "SKILL.md" }
      .sorted()
      .forEach { skillFile -> recordSkillTarget(repoRoot, discovered, skillFile) }
  }
  return discovered
}

private fun restoreFiles(originalBytes: Map<Path, ByteArray>) {
  originalBytes.forEach { (path, bytes) -> Files.write(path, bytes) }
}

private fun recordPackTargets(discovered: MutableMap<String, AuthoringTarget>, pack: PlatformManifest) {
  val baseline = pack.declaredFiles.baseline
  val displayName = pack.displayName ?: displayNameFromSlug(pack.slug)
  discovered[baseline.parent.name] =
    AuthoringTarget(
      baseline.parent.name,
      pack.slug,
      pack.slug,
      displayName,
      "code-review",
      "",
      baseline,
      baseline.resolveSibling("content.md"),
    )
  pack.declaredFiles.areas.forEach { (area, skillFile) ->
    discovered[skillFile.parent.name] =
      AuthoringTarget(
        skillFile.parent.name,
        pack.slug,
        pack.slug,
        displayName,
        "code-review",
        area,
        skillFile,
        skillFile.resolveSibling("content.md"),
      )
  }
  pack.declaredQualityCheckFile?.let { skillFile ->
    discovered[skillFile.parent.name] =
      AuthoringTarget(
        skillFile.parent.name,
        pack.slug,
        pack.slug,
        displayName,
        "quality-check",
        "",
        skillFile,
        skillFile.resolveSibling("content.md"),
      )
  }
}

private fun recordSkillTarget(repoRoot: Path, discovered: MutableMap<String, AuthoringTarget>, skillFile: Path) {
  val contentFile = skillFile.resolveSibling("content.md")
  if (!Files.isRegularFile(contentFile)) {
    return
  }
  val skillName = skillFile.parent.name
  if (skillName in discovered) {
    return
  }
  val platform = platformFromSkillPath(repoRoot.relativize(skillFile))
  val packageName = platform.ifBlank { "base" }
  val family = inferFamily(skillName)
  val displayName =
    platform.takeIf { it.isNotBlank() }?.let(::displayNameFromSlug)
      ?: displayNameFromSlug(skillName.removePrefix("bill-"))
  discovered[skillName] =
    AuthoringTarget(
      skillName,
      packageName,
      platform,
      displayName,
      family,
      inferArea(skillName, family),
      skillFile,
      contentFile,
    )
}

private fun platformFromSkillPath(relative: Path): String = if (
  relative.nameCount >= PRE_SHELL_PLATFORM_PATH_PARTS &&
  relative.getName(0).toString() == "skills" &&
  !relative.getName(1).toString().startsWith("bill-")
) {
  relative.getName(1).toString()
} else {
  ""
}
