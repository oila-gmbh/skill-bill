package skillbill.config.model

const val PROVIDER_PROFILES_KEY: String = "provider_profiles"

/** Environment variable the runtime reads once at the CLI seam to select a session overlay. */
const val SESSION_PROFILE_ENV_VAR: String = "SKILL_BILL_SESSION_PROFILE"

/**
 * A named environment preset a tier directive references by name. It carries only the environment
 * variable *name* for the token ([authTokenEnv]); no field ever holds a token value.
 */
data class ProviderProfile(
  val baseUrl: String? = null,
  val authTokenEnv: String? = null,
  val configDir: String? = null,
  val unset: Set<String> = emptySet(),
) {
  init {
    require((baseUrl != null) || (authTokenEnv != null) || (configDir != null) || unset.isNotEmpty()) {
      "ProviderProfile must declare at least one of base_url, auth_token_env, config_dir, unset."
    }
  }
}

sealed interface ProviderProfileParse {
  data class Valid(val profiles: Map<String, ProviderProfile>) : ProviderProfileParse

  data class Invalid(
    val keyPath: String,
    val value: String,
    val reason: String,
  ) : ProviderProfileParse
}

fun parseProviderProfiles(raw: Any?): ProviderProfileParse = try {
  ProviderProfileParse.Valid(parseProviderProfilesMapping(raw))
} catch (failure: InvalidProviderProfiles) {
  failure.invalid
}

private fun parseProviderProfilesMapping(raw: Any?): Map<String, ProviderProfile> {
  val profiles = raw as? Map<*, *> ?: invalidProviderProfile(
    PROVIDER_PROFILES_KEY,
    raw,
    "must be a mapping.",
  )
  return profiles.entries.associate { (rawName, rawProfile) ->
    val name = rawName as? String ?: invalidProviderProfile(
      "$PROVIDER_PROFILES_KEY.$rawName",
      rawProfile,
      "is not a non-blank profile name.",
    )
    if (name.isBlank()) {
      invalidProviderProfile("$PROVIDER_PROFILES_KEY.$name", rawProfile, "is not a non-blank profile name.")
    }
    name to parseProviderProfile(name, rawProfile)
  }
}

private fun parseProviderProfile(name: String, raw: Any?): ProviderProfile {
  val path = "$PROVIDER_PROFILES_KEY.$name"
  val profile = raw as? Map<*, *> ?: invalidProviderProfile(path, raw, "must be a mapping.")
  val fields = profile.entries.associate { (key, value) -> key.toString() to value }
  fields.entries.firstOrNull { (key, _) -> key !in PROFILE_FIELDS }?.let { (key, value) ->
    invalidProviderProfile("$path.$key", value, "is not a supported provider profile field.")
  }
  val baseUrl = nonBlankField(path, BASE_URL_KEY, fields[BASE_URL_KEY])
  val authTokenEnv = nonBlankField(path, AUTH_TOKEN_ENV_KEY, fields[AUTH_TOKEN_ENV_KEY])
  val configDir = nonBlankField(path, CONFIG_DIR_KEY, fields[CONFIG_DIR_KEY])
  val unset = when (val value = fields[UNSET_KEY]) {
    null -> emptySet()
    is List<*> -> value.mapIndexed { index, element ->
      if (element !is String || element.isBlank()) {
        invalidProviderProfile("$path.$UNSET_KEY[$index]", element, "must be a non-blank string.")
      }
      element
    }.toSet()
    else -> invalidProviderProfile("$path.$UNSET_KEY", value, "must be a list.")
  }
  if (listOfNotNull(baseUrl, authTokenEnv, configDir).isEmpty() && unset.isEmpty()) {
    invalidProviderProfile(path, raw, "must declare at least one field.")
  }
  return ProviderProfile(
    baseUrl = baseUrl,
    authTokenEnv = authTokenEnv,
    configDir = configDir,
    unset = unset,
  )
}

private fun nonBlankField(path: String, key: String, value: Any?): String? = when (value) {
  null -> null
  is String -> if (value.isBlank()) {
    invalidProviderProfile("$path.$key", value, "must be a non-blank string.")
  } else {
    value
  }
  else -> invalidProviderProfile("$path.$key", value, "must be a non-blank string.")
}

/**
 * The profile applied to a spawned phase child: an [environment] map to set on top of (never
 * clobbering skill-bill or compaction keys after remixing in the builder) and the [removals] list of
 * inherited variable names to drop. The token value exists only inside the returned in-memory map;
 * it is never persisted or serialized.
 */
data class ResolvedProviderProfile(
  val environment: Map<String, String>,
  val removals: Set<String>,
) {
  init {
    require(environment.keys.none(String::isBlank)) {
      "ResolvedProviderProfile environment keys must be non-blank."
    }
    require(removals.none(String::isBlank)) { "ResolvedProviderProfile removal keys must be non-blank." }
  }
}

/** Resolves a profile's presets against a concrete environment map. Pure; no ambient reads. */
fun ProviderProfile.resolveFor(environment: Map<String, String>): ResolvedProviderProfile {
  val env = buildMap {
    baseUrl?.let { put(ANTHROPIC_BASE_URL, it) }
    authTokenEnv?.let { name -> environment[name]?.let { put(ANTHROPIC_AUTH_TOKEN, it) } }
    configDir?.let { dir -> put(CLAUDE_CONFIG_DIR, expandTilde(dir, environment)) }
  }
  return ResolvedProviderProfile(environment = env, removals = unset)
}

private fun expandTilde(path: String, environment: Map<String, String>): String {
  if (!path.startsWith("~")) return path
  val home = environment["HOME"] ?: return path
  // A bare "~" yields the home directory; "~/…" yields home plus the remainder.
  return home + path.substring(1)
}

private fun invalidProviderProfile(keyPath: String, value: Any?, reason: String): Nothing =
  throw InvalidProviderProfiles(
    ProviderProfileParse.Invalid(keyPath = keyPath, value = value?.toString() ?: "null", reason = reason),
  )

private class InvalidProviderProfiles(
  val invalid: ProviderProfileParse.Invalid,
) : RuntimeException()

private const val BASE_URL_KEY: String = "base_url"
private const val AUTH_TOKEN_ENV_KEY: String = "auth_token_env"
private const val CONFIG_DIR_KEY: String = "config_dir"
private const val UNSET_KEY: String = "unset"
private const val ANTHROPIC_BASE_URL: String = "ANTHROPIC_BASE_URL"
private const val ANTHROPIC_AUTH_TOKEN: String = "ANTHROPIC_AUTH_TOKEN"
private const val CLAUDE_CONFIG_DIR: String = "CLAUDE_CONFIG_DIR"
private val PROFILE_FIELDS: Set<String> = setOf(BASE_URL_KEY, AUTH_TOKEN_ENV_KEY, CONFIG_DIR_KEY, UNSET_KEY)
