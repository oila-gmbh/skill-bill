package skillbill.application.decomposition

import skillbill.application.workflow.repoRoot
import java.math.BigDecimal
import java.math.BigInteger
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
  val match = Regex("^([A-Z][A-Z0-9]+-\\d+(?:\\.\\d+)?)-(.+)$").matchEntire(directoryName)
  if (match != null) {
    return match.groupValues[1] to match.groupValues[2]
  }
  val parts = directoryName.split("-", limit = 2)
  return parts.first() to parts.getOrElse(1) { "decomposition" }
}

internal fun Map<String, Any?>.optionalIntValue(key: String, sourceLabel: String): Int? = if (containsKey(key)) {
  this[key].asInt(sourceLabel, key)
} else {
  null
}

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
  is Byte, is Short, is Int -> (this as Number).toInt()
  is Long -> takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
  is BigInteger -> runCatching { intValueExact() }.getOrNull()
  is BigDecimal -> runCatching { intValueExact() }.getOrNull()
  is Float, is Double -> {
    val doubleValue = (this as Number).toDouble()
    runCatching {
      require(doubleValue.isFinite())
      require(doubleValue >= Int.MIN_VALUE.toDouble())
      require(doubleValue <= Int.MAX_VALUE.toDouble())
      BigDecimal.valueOf(doubleValue).intValueExact()
    }.getOrNull()?.takeIf { this is Double || it.toFloat() == this }
  }
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
