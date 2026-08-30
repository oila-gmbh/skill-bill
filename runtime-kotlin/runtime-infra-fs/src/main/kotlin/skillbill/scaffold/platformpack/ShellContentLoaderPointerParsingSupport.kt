
package skillbill.scaffold.platformpack

import skillbill.scaffold.model.PointerSpec
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal fun parsePointerEntry(slug: String, skillRelativeDir: String, entry: Map<*, *>): PointerSpec {
  val name = entry["name"] as? String
    ?: invalidManifestSchema(
      slug,
      "Platform pack '$slug': 'pointers[$skillRelativeDir]' entry is missing string field 'name'.",
    )
  val target = entry["target"] as? String
    ?: invalidManifestSchema(
      slug,
      "Platform pack '$slug': 'pointers[$skillRelativeDir]' entry '$name' is missing string field 'target'.",
    )
  if (target.isBlank()) {
    invalidManifestSchema(
      slug,
      "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' must declare a non-empty 'target'.",
    )
  }
  requireSafePointerTarget(slug, skillRelativeDir, name, target)
  return PointerSpec(skillRelativeDir = skillRelativeDir, name = name, target = target)
}

internal fun requireSafePointerSubpath(slug: String, value: String, label: String) {
  if (value.startsWith("/") || value.startsWith("\\")) {
    invalidManifestSchema(
      slug,
      "Platform pack '$slug': $label '$value' must be a relative path (no leading '/').",
    )
  }
  val asPath = try {
    Path.of(value)
  } catch (error: InvalidPathException) {
    invalidManifestSchemaFromPath(
      "Platform pack '$slug': $label '$value' is not a valid path: ${error.message}",
      error,
    )
  }
  if (asPath.isAbsolute) {
    invalidManifestSchema(
      slug,
      "Platform pack '$slug': $label '$value' must be relative, not absolute.",
    )
  }
  asPath.iterator().forEachRemaining { segment ->
    if (segment.toString() == "..") {
      invalidManifestSchema(
        slug,
        "Platform pack '$slug': $label '$value' must not contain '..' segments.",
      )
    }
  }
}

internal fun requireSafePointerTarget(slug: String, skillRelativeDir: String, name: String, target: String) {
  if (target.startsWith("/") || target.startsWith("\\")) {
    invalidManifestSchema(
      slug,
      "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' target '$target' must be a " +
        "repo-relative path (no leading '/').",
    )
  }
  val asPath = try {
    Path.of(target)
  } catch (error: InvalidPathException) {
    invalidManifestSchemaFromPath(
      "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' target '$target' is not a valid path: " +
        "${error.message}",
      error,
    )
  }
  if (asPath.isAbsolute) {
    invalidManifestSchema(
      slug,
      "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' target '$target' must be a " +
        "repo-relative path, not absolute.",
    )
  }
  asPath.iterator().forEachRemaining { segment ->
    if (segment.toString() == "..") {
      invalidManifestSchema(
        slug,
        "Platform pack '$slug': pointer '$name' under '$skillRelativeDir' target '$target' must not contain " +
          "'..' segments.",
      )
    }
  }
}
