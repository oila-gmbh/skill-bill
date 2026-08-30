@file:Suppress("MaxLineLength", "TooGenericExceptionCaught")

package skillbill.scaffold.platformpack

import skillbill.scaffold.model.PointerSpec
import java.nio.file.Path

internal fun parsePointers(manifest: Map<*, *>, slug: String): List<PointerSpec> {
  val raw = manifest["pointers"] ?: return emptyList()
  val pointersMap = raw as? Map<*, *>
    ?: invalidManifestSchema(slug,
      "Platform pack '$slug': 'pointers' must be a mapping of skill-relative-dir to a list of pointer entries.",
    )
  val seen = mutableSetOf<Pair<String, String>>()
  val collected = mutableListOf<PointerSpec>()
  for ((dirKey, entriesRaw) in pointersMap) {
    val skillRelativeDir = dirKey as? String
      ?: invalidManifestSchema(slug,
        "Platform pack '$slug': 'pointers' keys must be strings (skill-relative directory paths).",
      )
    if (skillRelativeDir.isBlank()) {
      invalidManifestSchema(slug,
        "Platform pack '$slug': 'pointers' skill-relative directory must be a non-empty string.",
      )
    }
    requireSafePointerSubpath(slug, skillRelativeDir, "pointers skill-relative directory")
    val entriesList = entriesRaw as? List<*>
      ?: invalidManifestSchema(slug,
        "Platform pack '$slug': 'pointers[$skillRelativeDir]' must be a list of {name, target} entries.",
      )
    for (entry in entriesList) {
      val entryMap = entry as? Map<*, *>
        ?: invalidManifestSchema(slug,
          "Platform pack '$slug': 'pointers[$skillRelativeDir]' entries must be mappings with name and target.",
        )
      val spec = parsePointerEntry(slug, skillRelativeDir, entryMap)
      val key = spec.skillRelativeDir to spec.name
      if (!seen.add(key)) {
        invalidManifestSchema(slug,
          "Platform pack '$slug': duplicate pointer entry '${spec.name}' under '${spec.skillRelativeDir}'.",
        )
      }
      collected += spec
    }
  }
  return collected
}

internal const val FEATURE_TASK_ADDON_CONSUMER: String = "feature-task"

internal fun parseOptionalPath(manifest: Map<*, *>, slug: String, key: String, packRoot: Path): Path? {
  val raw = manifest[key] ?: return null
  val value = raw as? String
    ?: invalidManifestSchema(slug, "Platform pack '$slug': '$key' must be a non-empty path string when provided.")
  if (value.isBlank()) {
    invalidManifestSchema(slug, "Platform pack '$slug': '$key' must be a non-empty path string when provided.")
  }
  return packRoot.resolve(value).normalize()
}
