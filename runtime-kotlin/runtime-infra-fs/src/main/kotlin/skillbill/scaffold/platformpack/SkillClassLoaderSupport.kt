@file:Suppress("MaxLineLength", "TooGenericExceptionCaught")
package skillbill.scaffold.platformpack

import org.yaml.snakeyaml.Yaml
import skillbill.error.InvalidManifestSchemaError
import skillbill.scaffold.model.SkillClassManifest
import skillbill.scaffold.model.SkillClassMatcher
import skillbill.scaffold.model.SkillClassSection
import java.nio.file.Files
import java.nio.file.Path

internal fun SkillClassManifest.matchesSkillName(skillName: String): Boolean {
  if (matchers.any { matcher -> matcher.excludeExact.contains(skillName) }) {
    return false
  }
  return matchers.any { matcher -> matcher.matchesSkillName(skillName) }
}

internal fun SkillClassMatcher.matchesSkillName(skillName: String): Boolean {
  val notExcluded = !excludeExact.contains(skillName)
  val localPattern = pattern
  val patternMatches = localPattern != null && localPattern.matches(skillName)
  return notExcluded && (exact == skillName || patternMatches)
}

internal fun readClassManifestYaml(classFile: Path, classId: String): Any? = try {
  Yaml().load<Any?>(Files.readString(classFile))
} catch (error: Exception) {
  throw InvalidManifestSchemaError(
    "Skill class '$classId': manifest '$classFile' is not valid YAML: ${error.message}",
    error,
  )
}

internal fun buildClassManifest(classId: String, classFile: Path, raw: Any?): SkillClassManifest {
  val manifest = requireSkillClassManifestMap(classId, classFile.toString(), raw)
  val declaredClass = requireSkillClassString(manifest, classId, "class")
  validateDeclaredSkillClass(classId, declaredClass)
  val contractVersion = requireSkillClassString(manifest, classId, "contract_version")
  validateSkillClassContractVersion(classId, contractVersion)
  val matchers = parseSkillClassMatchers(manifest, classId)
  val pointers = parseSkillClassStringList(manifest, classId, "pointers", required = false)
  val sections = parseSkillClassSections(manifest, classId)
  val ceremonyLines = parseSkillClassStringList(manifest, classId, "ceremony_lines", required = false)
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

internal fun parseSkillClassMatchers(manifest: Map<*, *>, classId: String): List<SkillClassMatcher> {
  val raw = manifest["matchers"]
    ?: throw InvalidManifestSchemaError("Skill class '$classId': required field 'matchers' is missing.")
  val matchersList = requireSkillClassMatcherList(classId, raw)
  if (matchersList.isEmpty()) {
    throw InvalidManifestSchemaError("Skill class '$classId': 'matchers' must declare at least one entry.")
  }
  return matchersList.mapIndexed { index, entry -> parseSkillClassMatcher(classId, index, entry) }
}

internal fun parseSkillClassSections(manifest: Map<*, *>, classId: String): List<SkillClassSection> {
  val raw = manifest["sections"] ?: return emptyList()
  val list = raw as? List<*>
    ?: throw InvalidManifestSchemaError("Skill class '$classId': 'sections' must be a list.")
  return list.mapIndexed { index, entry -> parseSkillClassSection(classId, index, entry) }
}

internal fun parseSkillClassStringList(manifest: Map<*, *>, classId: String, field: String, required: Boolean): List<String> {
  val raw = manifest[field]
  if (raw == null) {
    if (required) {
      throw InvalidManifestSchemaError("Skill class '$classId': required field '$field' is missing.")
    }
    return emptyList()
  }
  val list = raw as? List<*>
    ?: throw InvalidManifestSchemaError("Skill class '$classId': '$field' must be a list of strings.")
  return list.mapIndexed { index, entry -> requireSkillClassStringEntry(classId, field, index, entry) }
}

internal fun requireSkillClassString(manifest: Map<*, *>, classId: String, field: String): String {
  val raw = manifest[field]
    ?: throw InvalidManifestSchemaError("Skill class '$classId': required field '$field' is missing.")
  return raw as? String
    ?: throw InvalidManifestSchemaError("Skill class '$classId': field '$field' must be a string.")
}
