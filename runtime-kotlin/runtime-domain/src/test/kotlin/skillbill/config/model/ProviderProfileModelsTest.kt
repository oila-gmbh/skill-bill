package skillbill.config.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProviderProfileModelsTest {
  @Test
  fun `parses a profile with every field and one with only config_dir`() {
    val full = assertIs<ProviderProfileParse.Valid>(
      parseProviderProfiles(
        mapOf(
          "anthropic-default" to mapOf(
            "base_url" to "https://api.anthropic.com",
            "auth_token_env" to "ANTHROPIC_AUTH_TOKEN",
            "config_dir" to "~/.claude",
            "unset" to listOf("ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL"),
          ),
          "minimal" to mapOf("config_dir" to "~/.claude-min"),
        ),
      ),
    )

    val anthropic = full.profiles.getValue("anthropic-default")
    assertEquals("https://api.anthropic.com", anthropic.baseUrl)
    assertEquals("ANTHROPIC_AUTH_TOKEN", anthropic.authTokenEnv)
    assertEquals("~/.claude", anthropic.configDir)
    assertEquals(setOf("ANTHROPIC_MODEL", "ANTHROPIC_SMALL_FAST_MODEL"), anthropic.unset)
    assertEquals("~/.claude-min", full.profiles.getValue("minimal").configDir)
  }

  @Test
  fun `a parsed profile exposes only a token variable name, never a token value`() {
    val valid = assertIs<ProviderProfileParse.Valid>(
      parseProviderProfiles(mapOf("p" to mapOf("auth_token_env" to "DEEPINFRA_TOKEN"))),
    )
    assertTrue(valid.profiles.getValue("p").authTokenEnv == "DEEPINFRA_TOKEN")
  }

  @Test
  fun `rejects malformed profile entries with keyPath value and reason`() {
    val cases = listOf(
      Pair(mapOf("p" to mapOf("unknown" to "x")), "provider_profiles.p.unknown"),
      Pair(mapOf("" to mapOf("config_dir" to "~/.claude")), "provider_profiles."),
      Pair(mapOf("p" to mapOf("base_url" to "")), "provider_profiles.p.base_url"),
      Pair(mapOf("p" to mapOf("unset" to "not-a-list")), "provider_profiles.p.unset"),
      Pair(mapOf("p" to mapOf("unset" to listOf("A", ""))), "provider_profiles.p.unset[1]"),
      Pair(mapOf("p" to emptyMap<String, Any?>()), "provider_profiles.p"),
    )

    cases.forEach { (raw, expectedPath) ->
      val invalid = assertIs<ProviderProfileParse.Invalid>(parseProviderProfiles(raw))
      assertEquals(expectedPath, invalid.keyPath)
      assertTrue(invalid.reason.isNotBlank(), "reason must be non-blank for ${invalid.keyPath}")
    }
  }

  @Test
  fun `resolveFor sets every variable when all fields are present`() {
    val profile = ProviderProfile(
      baseUrl = "https://api.anthropic.com",
      authTokenEnv = "ANTHROPIC_AUTH_TOKEN",
      configDir = "~/.claude",
      unset = setOf("ANTHROPIC_MODEL"),
    )
    val resolved = profile.resolveFor(mapOf("HOME" to "/home/u", "ANTHROPIC_AUTH_TOKEN" to "tok-123"))
    assertEquals("https://api.anthropic.com", resolved.environment["ANTHROPIC_BASE_URL"])
    assertEquals("tok-123", resolved.environment["ANTHROPIC_AUTH_TOKEN"])
    assertEquals("/home/u/.claude", resolved.environment["CLAUDE_CONFIG_DIR"])
    assertEquals(setOf("ANTHROPIC_MODEL"), resolved.removals)
  }

  @Test
  fun `resolveFor omits config_dir entirely when the profile has none`() {
    // AC-009: a profile omitting config_dir leaves the session CLAUDE_CONFIG_DIR untouched.
    val profile = ProviderProfile(authTokenEnv = "MISSING")
    val resolved = profile.resolveFor(emptyMap())
    assertEquals(emptyMap<String, String>(), resolved.environment)
    assertTrue("ANTHROPIC_AUTH_TOKEN" !in resolved.environment)
    assertTrue("CLAUDE_CONFIG_DIR" !in resolved.environment)
  }

  @Test
  fun `resolveFor leaves a tilde path verbatim when HOME is absent`() {
    val profile = ProviderProfile(configDir = "~/config")
    val resolved = profile.resolveFor(emptyMap())
    assertEquals("~/config", resolved.environment["CLAUDE_CONFIG_DIR"])
  }
}
