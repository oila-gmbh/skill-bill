package skillbill.scaffold.platformpack

import skillbill.error.InvalidManifestSchemaError
import skillbill.nativeagent.platformpack.readRequiredRubricCompanions
import java.nio.file.Path

internal fun parseRequiredRubricCompanions(
  manifest: Map<*, *>,
  packRoot: Path,
  areas: Map<String, Path>,
): Map<String, List<String>> {
  val raw = manifest["required_rubric_companions"] ?: return emptyMap()
  val map = raw as? Map<*, *> ?: throw InvalidManifestSchemaError("Required rubric companions must be a mapping.")
  return map.entries.associate { (key, value) ->
    val area = companionArea(key)
    val content = areas[area] ?: throw InvalidManifestSchemaError("Required companion names an undeclared specialist.")
    val names = companionNames(value)
    readRequiredRubricCompanions(packRoot, content, names)
    area to names
  }
}

private fun companionArea(value: Any?): String =
  value as? String ?: throw InvalidManifestSchemaError("Companion area must be a string.")

private fun companionNames(value: Any?): List<String> = (value as? List<*>)?.map {
  it as? String ?: throw InvalidManifestSchemaError("Companion filename must be a string.")
} ?: throw InvalidManifestSchemaError("Required companions must be a list.")
