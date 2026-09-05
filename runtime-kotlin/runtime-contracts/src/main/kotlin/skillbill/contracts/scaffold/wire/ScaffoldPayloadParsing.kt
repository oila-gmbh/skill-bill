package skillbill.contracts.scaffold.wire

import skillbill.error.InvalidScaffoldPayloadError

/**
 * SKILL-52.2 subtask 2: shared raw-map extraction primitives for the scaffold wire-payload
 * adapter parsers (CLI, MCP). These helpers loud-fail with [InvalidScaffoldPayloadError] when a
 * required field is missing or has the wrong type — preserving the existing wire-level seam
 * behavior of `runtime-domain` policy functions while keeping all `Map<String, Any?>` handling
 * inside `runtime-contracts` (which is **not** scanned by the architecture raw-map rule).
 *
 * The raw-map architecture scanner in
 * `runtime-core/src/test/kotlin/skillbill/architecture/RuntimeArchitectureTest.kt`
 * (`findRawMapViolations`) walks only `runtime-application`, `runtime-domain`, and `runtime-ports`
 * source roots — `runtime-contracts` is exempt by design — so these public top-level functions
 * do NOT require an allow-list entry.
 *
 * To preserve the legacy wire-shape, the user-facing error message strings MUST match the
 * historical messages from the previous `runtime-domain` `requireString` / `requireStringOrDefault`
 * policy functions verbatim (CLI JSON error fields and MCP error envelopes are wire-shape stable
 * across this seam).
 */

/**
 * Requires [map] to contain a value of type [T] under [key]. Loud-fails with
 * [InvalidScaffoldPayloadError] when the field is missing or has the wrong type. The error
 * message reports the expected and observed types so adapter callers do not need to translate
 * raw [ClassCastException]s.
 */
inline fun <reified T : Any> requireScalar(map: Map<String, Any?>, key: String): T {
  val value = map[key]
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' is missing (expected ${T::class.simpleName}).",
    )
  if (value !is T) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' has wrong type " +
        "(expected ${T::class.simpleName}, got ${value::class.simpleName}).",
    )
  }
  return value
}

/**
 * Requires [map] to contain a non-blank `String` under [key]. Mirrors the historical
 * `runtime-domain` `requireString` policy helper.
 */
fun requireString(map: Map<String, Any?>, key: String): String {
  val value = map[key] as? String
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a non-empty string.",
    )
  if (value.isBlank()) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a non-empty string.",
    )
  }
  return value
}

/**
 * Returns the value at [key] in [map] when it is a non-blank `String`; otherwise returns
 * [default]. Mirrors the historical `runtime-domain` `requireStringOrDefault` policy helper —
 * it never throws.
 */
fun requireStringOrDefault(map: Map<String, Any?>, key: String, default: String): String =
  (map[key] as? String)?.takeIf { it.isNotBlank() } ?: default

/**
 * Requires [map] to contain a value at [key] that is `Number`-convertible to `Int`. Accepts any
 * [Number] so a JSON round-trip that materialised a `Long` is still lifted cleanly.
 */
fun requireInt(map: Map<String, Any?>, key: String): Int {
  val value = map[key]
    ?: throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' is missing (expected Int).",
    )
  if (value !is Number) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' has wrong type " +
        "(expected Number-convertible Int, got ${value::class.simpleName}).",
    )
  }
  return value.toInt()
}

/**
 * Returns the value at [key] when it is a non-blank `String`; otherwise null. Distinguishes
 * "field absent" from "field present but blank" by returning null in both cases — adapter
 * parsers that need that distinction must check the map key directly.
 */
fun optionalString(map: Map<String, Any?>, key: String): String? = (map[key] as? String)?.takeIf { it.isNotBlank() }

/**
 * Returns the value at [key] when it is a `List<*>`; null when the field is absent. Loud-fails
 * with [InvalidScaffoldPayloadError] when the field is present but is not a list.
 */
fun optionalList(map: Map<String, Any?>, key: String): List<*>? {
  val raw = map[key] ?: return null
  if (raw !is List<*>) {
    throw InvalidScaffoldPayloadError(
      "Scaffold payload field '$key' must be a list when provided " +
        "(got ${raw::class.simpleName}).",
    )
  }
  return raw
}
