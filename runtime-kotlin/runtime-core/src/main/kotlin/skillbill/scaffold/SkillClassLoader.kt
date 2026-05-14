@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "ThrowsCount", "TooManyFunctions")

package skillbill.scaffold

import org.yaml.snakeyaml.Yaml
import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.MissingManifestError
import skillbill.scaffold.model.SkillClassManifest
import skillbill.scaffold.model.SkillClassMatcher
import skillbill.scaffold.model.SkillClassSection
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.useDirectoryEntries

internal const val SKILL_CLASSES_DIR = "orchestration/skill-classes"

/**
 * Walk up from [start] until we find a directory containing [SKILL_CLASSES_DIR]. The renderer uses
 * this so callers do not have to thread the repo root parameter through every callsite.
 */
internal fun findRepoRootForSkillClasses(start: Path): Path? {
  var current: Path? = start.toAbsolutePath().normalize().let { if (Files.isDirectory(it)) it else it.parent }
  while (current != null) {
    if (Files.isDirectory(current.resolve(SKILL_CLASSES_DIR))) {
      return current
    }
    current = current.parent
  }
  return null
}

/**
 * Resolve a skill class by name, given a starting path inside the repo. Returns null when no class
 * matches — callers decide whether that is a hard error for this skill (governed shells) or fine
 * (horizontal skills without framework-class behavior).
 */
internal fun resolveSkillClassForSkill(skillName: String, startPath: Path): SkillClassManifest? {
  val repoRoot = findRepoRootForSkillClasses(startPath) ?: return null
  return resolveSkillClass(skillName, discoverSkillClasses(repoRoot))
}

/**
 * Load every `orchestration/skill-classes/<class>.yaml` under [repoRoot]. Each YAML is required to
 * be a well-formed [SkillClassManifest]. The returned list is sorted by class id so resolution is
 * deterministic.
 */
internal fun discoverSkillClasses(repoRoot: Path): List<SkillClassManifest> {
  val classesDir = repoRoot.toAbsolutePath().normalize().resolve(SKILL_CLASSES_DIR)
  if (!Files.isDirectory(classesDir)) {
    throw MissingManifestError(
      "Skill classes directory '$classesDir' is missing. Every governed render needs at least the default class file.",
    )
  }
  val yamlFiles = classesDir.useDirectoryEntries("*.yaml") { stream -> stream.sorted().toList() }
  if (yamlFiles.isEmpty()) {
    throw MissingManifestError(
      "Skill classes directory '$classesDir' is empty. Expected at least one <class>.yaml file.",
    )
  }
  return yamlFiles.map(::loadSkillClassManifest)
}

/**
 * Resolve a single skill name against the loaded [classes]. Returns the matching class, or null
 * when no class declares it. Callers must decide whether "no class" is a hard error (governed
 * skills) or fine (free-form horizontal skills handled by the default class).
 */
internal fun resolveSkillClass(skillName: String, classes: List<SkillClassManifest>): SkillClassManifest? {
  val matches = classes.filter { manifest -> manifest.matches(skillName) }
  return when {
    matches.isEmpty() -> null
    matches.size == 1 -> matches.single()
    else -> throw InvalidManifestSchemaError(
      "Skill '$skillName' matches more than one class: ${matches.map { it.classId }.sorted()}. " +
        "Tighten the matchers (use exclude_exact or narrower patterns) so each skill resolves to exactly one class.",
    )
  }
}

private fun SkillClassManifest.matches(skillName: String): Boolean {
  if (matchers.any { matcher -> matcher.excludeExact.contains(skillName) }) {
    return false
  }
  return matchers.any { matcher -> matcher.matches(skillName) }
}

private fun SkillClassMatcher.matches(skillName: String): Boolean {
  val notExcluded = !excludeExact.contains(skillName)
  val localPattern = pattern
  val patternMatches = localPattern != null && localPattern.matches(skillName)
  return notExcluded && (exact == skillName || patternMatches)
}

internal fun loadSkillClassManifest(classFile: Path): SkillClassManifest {
  val resolved = classFile.toAbsolutePath().normalize()
  if (!Files.isRegularFile(resolved)) {
    throw MissingManifestError("Skill class manifest '$resolved' is missing.")
  }
  val classId = resolved.fileName.toString().removeSuffix(".yaml")
  val raw = readClassManifestYaml(resolved, classId)
  return buildClassManifest(classId, resolved, raw)
}

private fun readClassManifestYaml(classFile: Path, classId: String): Any? = try {
  Yaml().load<Any?>(Files.readString(classFile))
} catch (error: Exception) {
  throw InvalidManifestSchemaError(
    "Skill class '$classId': manifest '$classFile' is not valid YAML: ${error.message}",
    error,
  )
}

private fun buildClassManifest(classId: String, classFile: Path, raw: Any?): SkillClassManifest {
  val manifest = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Skill class '$classId': manifest '$classFile' must be a YAML mapping at the top level.",
    )

  val declaredClass = requireString(manifest, classId, "class")
  if (declaredClass != classId) {
    throw InvalidManifestSchemaError(
      "Skill class '$classId': manifest 'class' field is '$declaredClass', expected '$classId' to match the filename.",
    )
  }

  val contractVersion = requireString(manifest, classId, "contract_version")
  if (contractVersion != SHELL_CONTRACT_VERSION) {
    throw ContractVersionMismatchError(
      "Skill class '$classId': declares contract_version '$contractVersion' " +
        "but the shell expects '$SHELL_CONTRACT_VERSION'.",
    )
  }

  val matchers = parseMatchers(manifest, classId)
  val pointers = parseStringList(manifest, classId, "pointers", required = false)
  val sections = parseSections(manifest, classId)
  val ceremonyLines = parseStringList(manifest, classId, "ceremony_lines", required = false)

  return SkillClassManifest(
    classId = classId,
    classFile = classFile,
    contractVersion = contractVersion,
    matchers = matchers,
    pointers = pointers,
    sections = sections,
    ceremonyLines = ceremonyLines,
  )
}

private fun parseMatchers(manifest: Map<*, *>, classId: String): List<SkillClassMatcher> {
  val raw = manifest["matchers"]
    ?: throw InvalidManifestSchemaError("Skill class '$classId': required field 'matchers' is missing.")
  val matchersList = raw as? List<*>
    ?: throw InvalidManifestSchemaError("Skill class '$classId': 'matchers' must be a list.")
  if (matchersList.isEmpty()) {
    throw InvalidManifestSchemaError("Skill class '$classId': 'matchers' must declare at least one entry.")
  }
  return matchersList.mapIndexed { index, entry -> parseMatcher(classId, index, entry) }
}

@Suppress("CyclomaticComplexMethod")
private fun parseMatcher(classId: String, index: Int, raw: Any?): SkillClassMatcher {
  val entry = raw as? Map<*, *>
    ?: throw InvalidManifestSchemaError(
      "Skill class '$classId': matcher #$index must be a YAML mapping with 'exact' or 'pattern'.",
    )
  val exact = entry["exact"]?.let { value ->
    value as? String
      ?: throw InvalidManifestSchemaError("Skill class '$classId': matcher #$index field 'exact' must be a string.")
  }
  val patternString = entry["pattern"]?.let { value ->
    value as? String
      ?: throw InvalidManifestSchemaError("Skill class '$classId': matcher #$index field 'pattern' must be a string.")
  }
  if (exact == null && patternString == null) {
    throw InvalidManifestSchemaError(
      "Skill class '$classId': matcher #$index must declare either 'exact' or 'pattern'.",
    )
  }
  if (exact != null && patternString != null) {
    throw InvalidManifestSchemaError(
      "Skill class '$classId': matcher #$index must declare exactly one of 'exact' or 'pattern', not both.",
    )
  }
  val excludeExactRaw = entry["exclude_exact"]
  val excludeExact: List<String> = when (excludeExactRaw) {
    null -> emptyList()
    is List<*> -> excludeExactRaw.map { value ->
      value as? String
        ?: throw InvalidManifestSchemaError(
          "Skill class '$classId': matcher #$index 'exclude_exact' entries must be strings.",
        )
    }
    else -> throw InvalidManifestSchemaError(
      "Skill class '$classId': matcher #$index 'exclude_exact' must be a list of strings.",
    )
  }
  val pattern = patternString?.let { source ->
    runCatching { Regex(source) }.getOrElse { error ->
      throw InvalidManifestSchemaError(
        "Skill class '$classId': matcher #$index pattern '$source' is not a valid regex: ${error.message}",
      )
    }
  }
  return SkillClassMatcher(exact = exact, pattern = pattern, excludeExact = excludeExact)
}

private fun parseSections(manifest: Map<*, *>, classId: String): List<SkillClassSection> {
  val raw = manifest["sections"] ?: return emptyList()
  val list = raw as? List<*>
    ?: throw InvalidManifestSchemaError("Skill class '$classId': 'sections' must be a list.")
  return list.mapIndexed { index, entry ->
    val mapping = entry as? Map<*, *>
      ?: throw InvalidManifestSchemaError(
        "Skill class '$classId': sections[$index] must be a mapping with 'heading' and 'body'.",
      )
    val heading = (mapping["heading"] as? String)?.trim().orEmpty()
    if (heading.isEmpty()) {
      throw InvalidManifestSchemaError("Skill class '$classId': sections[$index] is missing required 'heading'.")
    }
    val body = mapping["body"] as? String
      ?: throw InvalidManifestSchemaError("Skill class '$classId': sections[$index] is missing required 'body'.")
    SkillClassSection(heading = heading, body = body.trimEnd())
  }
}

private fun parseStringList(manifest: Map<*, *>, classId: String, field: String, required: Boolean): List<String> {
  val raw = manifest[field]
    ?: if (required) {
      throw InvalidManifestSchemaError("Skill class '$classId': required field '$field' is missing.")
    } else {
      return emptyList()
    }
  val list = raw as? List<*>
    ?: throw InvalidManifestSchemaError("Skill class '$classId': '$field' must be a list of strings.")
  return list.mapIndexed { index, entry ->
    entry as? String
      ?: throw InvalidManifestSchemaError("Skill class '$classId': '$field'[$index] must be a string.")
  }
}

private fun requireString(manifest: Map<*, *>, classId: String, field: String): String {
  val raw = manifest[field]
    ?: throw InvalidManifestSchemaError("Skill class '$classId': required field '$field' is missing.")
  return raw as? String
    ?: throw InvalidManifestSchemaError("Skill class '$classId': field '$field' must be a string.")
}
