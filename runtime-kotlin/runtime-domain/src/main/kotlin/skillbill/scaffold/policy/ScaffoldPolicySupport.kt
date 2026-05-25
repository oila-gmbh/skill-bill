package skillbill.scaffold.policy

import skillbill.error.InvalidScaffoldPayloadError

/**
 * SKILL-52.1 subtask 2: pure-policy payload helpers extracted from the `runtime-infra-fs`
 * scaffold service. These helpers loud-fail with [InvalidScaffoldPayloadError] when payload
 * shape contracts are violated — preserving the existing wire-level seam behavior.
 */

// SKILL-52.2 subtask 2 (Task 11): `requireString` and `requireStringOrDefault` were retired from
// this file. Their public raw-`Map<String, Any?>` signatures were the source of two of the 11
// scaffold input raw-map allow-list entries. CLI / MCP adapters now parse their wire payloads
// using `skillbill.contracts.scaffold.wire.ScaffoldPayloadParseSupport.requireString` /
// `requireStringOrDefault` (which live in `runtime-contracts`, a module the architecture scanner
// does not walk); the legacy filesystem orchestrator inside `runtime-infra-fs` keeps a private
// `requireStringMap` / `requireStringOrDefaultMap` copy for its internal raw-map flow (see
// `runtime-infra-fs/.../scaffold/ScaffoldPayloadMapPolicy.kt`).

/**
 * Requires [value] to be a list of non-blank strings. Throws [InvalidScaffoldPayloadError]
 * tagged with [fieldName] when the shape contract is violated.
 */
fun requireStringList(value: Any?, fieldName: String): List<String> {
  if (value !is List<*>) {
    failInvalidScaffoldPayload("Scaffold payload field '$fieldName' must be a list of strings.")
  }
  val mapped = value.map { it as? String ?: failNonBlankString(fieldName) }
  if (mapped.any(String::isBlank)) {
    failNonBlankString(fieldName)
  }
  return mapped
}

private fun failNonBlankString(fieldName: String): Nothing =
  failInvalidScaffoldPayload("Scaffold payload field '$fieldName' must contain only non-empty strings.")

private fun failInvalidScaffoldPayload(message: String): Nothing = throw InvalidScaffoldPayloadError(message)

/**
 * Requires [map] to contain a non-blank string under [key]. The error message uses [fieldLabel]
 * (typically a dotted path like "baseline_layers[0].scope") for caller-facing diagnostics.
 */
fun requireStringInPayloadMap(map: Map<*, *>, fieldLabel: String, key: String): String {
  val value = map[key] as? String
    ?: throw InvalidScaffoldPayloadError("Scaffold payload field '$fieldLabel' must be a non-empty string.")
  if (value.isBlank()) {
    throw InvalidScaffoldPayloadError("Scaffold payload field '$fieldLabel' must be a non-empty string.")
  }
  return value
}

/**
 * Derives a human-readable display name from a kebab-case slug. Used by scaffold defaults and the
 * post-scaffold validation seam.
 */
fun displayNameFromSlug(slug: String): String = slug.split('-').joinToString(" ") { part ->
  part.replaceFirstChar { ch ->
    if (ch.isLowerCase()) ch.titlecase() else ch.toString()
  }
}

/**
 * Canonical note shared by every scaffold flow that creates a `content.md` companion file.
 * Kept here so the policy layer renders the same wording every time.
 */
fun sharedContractNote(): String = "Author skill instructions only in sibling `content.md` files. " +
  "Generated `SKILL.md` wrappers and platform pointer files are render/install output."
