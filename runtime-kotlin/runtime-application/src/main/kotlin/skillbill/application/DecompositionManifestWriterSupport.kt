package skillbill.application

import java.nio.file.Path

internal fun resolvedParentSpecPath(repoRoot: Path, parentSpecPath: Path): Path =
  if (parentSpecPath.isAbsolute) parentSpecPath.normalize() else repoRoot.resolve(parentSpecPath).normalize()

internal fun repoRelativePath(repoRoot: Path, path: Path): String {
  val root = repoRoot.toAbsolutePath().normalize()
  val absolute = resolvedParentSpecPath(root, path).toAbsolutePath().normalize()
  if (!absolute.startsWith(root)) {
    return absolute.toString()
  }
  return root.relativize(absolute).joinToString("/")
}

internal fun issueAndFeature(directoryName: String): Pair<String, String> {
  val match = Regex("^([A-Z]+-\\d+)-(.+)$").matchEntire(directoryName)
  if (match != null) {
    return match.groupValues[1] to match.groupValues[2]
  }
  val parts = directoryName.split("-", limit = 2)
  return parts.first() to parts.getOrElse(1) { "decomposition" }
}

internal fun Map<String, Any?>.intValueOrNull(key: String): Int? = this[key]?.asIntOrNull()

internal fun Map<String, Any?>.intValue(key: String, sourceLabel: String): Int =
  this[key].asIntOrNull() ?: invalidManifest(sourceLabel, "$key must be an integer.")

internal fun Map<String, Any?>.booleanValueOrDefault(key: String, default: Boolean, sourceLabel: String): Boolean =
  when (val value = this[key]) {
    null -> default
    is Boolean -> value
    else -> invalidManifest(sourceLabel, "$key must be a boolean.")
  }

internal fun Any?.asInt(sourceLabel: String, fieldPath: String): Int =
  asIntOrNull() ?: invalidManifest(sourceLabel, "$fieldPath must be an integer.")

internal fun Any?.asIntOrNull(): Int? = when (this) {
  is Int -> this
  is Long -> this.toInt()
  is Number -> this.toInt()
  else -> null
}

internal fun Any?.asStringAnyMap(sourceLabel: String, fieldPath: String): Map<String, Any?> =
  (this as? Map<*, *>)?.entries?.associateTo(LinkedHashMap<String, Any?>()) { (key, value) ->
    val stringKey = key as? String ?: invalidManifest(sourceLabel, "$fieldPath contains a non-string key.")
    stringKey to value
  } ?: invalidManifest(sourceLabel, "$fieldPath must be an object.")

internal fun Any?.asStringAnyMapOrNull(): Map<String, Any?>? =
  (this as? Map<*, *>)?.entries?.associateTo(LinkedHashMap<String, Any?>()) { (key, value) ->
    val stringKey = key as? String ?: return null
    stringKey to value
  }
