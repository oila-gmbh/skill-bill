package skillbill.infrastructure.fs

import org.yaml.snakeyaml.Yaml
import skillbill.error.ExternalAddonOverlayError
import java.nio.file.Files
import java.nio.file.Path

internal fun manifestStructureError(
  slug: String,
  field: String,
  error: ClassCastException,
): ExternalAddonOverlayError {
  val message = "Installed platform.yaml for '$slug' has unexpected structure in '$field': ${error.message}"
  return ExternalAddonOverlayError(message, error)
}

internal fun manifestStructureError(
  slug: String,
  field: String,
  expected: String,
  actual: Any?,
): ExternalAddonOverlayError {
  val found = actual?.javaClass?.simpleName ?: "null"
  val message = "Installed platform.yaml for '$slug' has unexpected structure in '$field': " +
    "expected $expected but found $found."
  return ExternalAddonOverlayError(message)
}

internal fun readRawManifest(manifestPath: Path): MutableMap<String, Any?> {
  val raw = Yaml().load<Any?>(Files.readString(manifestPath)) as? Map<*, *>
    ?: throw ExternalAddonOverlayError("Installed platform manifest '$manifestPath' must be a YAML mapping.")
  val root = linkedMapOf<String, Any?>()
  raw.forEach { (k, v) ->
    root[k as String] = when (v) {
      is Map<*, *> -> linkedMapOfFrom(v)
      else -> v
    }
  }
  return root
}

internal fun linkedMapOfFrom(map: Map<*, *>): MutableMap<String, Any?> {
  val out = linkedMapOf<String, Any?>()
  map.forEach { (k, v) ->
    out[k as String] = when (v) {
      is Map<*, *> -> linkedMapOfFrom(v)
      is List<*> -> v.mapTo(mutableListOf()) { item ->
        when (item) {
          is Map<*, *> -> linkedMapOfFrom(item)
          else -> item
        }
      }
      else -> v
    }
  }
  return out
}
