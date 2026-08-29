package skillbill.cli.scaffold

import skillbill.cli.core.CliRunState

internal fun MutableMap<String, AssistedPlatformProfile>.putProfile(
  slug: String,
  displayName: String,
  strongSignals: List<String>,
  vararg aliases: String,
) {
  val profile = AssistedPlatformProfile(slug = slug, displayName = displayName, strongSignals = strongSignals)
  aliases.forEach { alias -> put(languageLookupKey(alias), profile) }
}

internal fun fallbackAssistedPlatformProfile(input: String): AssistedPlatformProfile {
  val slug = platformSlugFromInput(input)
  val displayName = displayNameFromInput(input, slug)
  return AssistedPlatformProfile(
    slug = slug,
    displayName = displayName,
    strongSignals = listOf(".$slug", "$slug/"),
  )
}

internal fun languageLookupKey(value: String): String =
  value.trim().lowercase().filter { character -> character.isLetterOrDigit() || character == '#' || character == '+' }

internal fun platformSlugFromInput(value: String): String = value.trim()
  .lowercase()
  .replace(Regex("[^a-z0-9]+"), "-")
  .trim('-')
  .ifBlank { "platform" }

internal fun displayNameFromInput(input: String, slug: String): String =
  input.trim().takeIf { it.isNotBlank() } ?: slug.split("-").joinToString(" ") { part ->
    part.replaceFirstChar { character -> character.uppercase() }
  }

internal fun promptRequired(state: CliRunState, label: String): String {
  val value = promptOptional(state, label)
  require(value.isNotBlank()) { "Missing required scaffold wizard value: $label." }
  return value
}

internal fun promptOptional(state: CliRunState, label: String): String {
  state.liveStdout("$label: ")
  return state.readInputLine()?.trim().orEmpty()
}

internal fun normalizeBillSkillName(name: String): String = if (name.startsWith("bill-")) name else "bill-$name"
