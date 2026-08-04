package skillbill.cli

import com.github.ajalt.clikt.core.UsageError
import skillbill.cli.core.refuseUnresolvableProfileDirectives
import skillbill.config.model.PhaseModelDirective
import skillbill.config.model.ProviderProfile
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelDirectiveRefusalTest {
  private val profiles = mapOf(
    "anthropic-default" to ProviderProfile(
      baseUrl = "https://api.anthropic.com",
      authTokenEnv = "ANTHROPIC_AUTH_TOKEN",
      configDir = "~/.claude",
    ),
  )

  @Test
  fun `undeclared profile reference refuses before any launch, naming declared profiles`() {
    val error = assertFailsWith<UsageError> {
      refuseUnresolvableProfileDirectives(
        directivesByPhase = mapOf("plan" to PhaseModelDirective("opus", profile = "ghost")),
        resolvedAgentIdByPhase = mapOf("plan" to "claude"),
        profiles = profiles,
        environment = mapOf("ANTHROPIC_AUTH_TOKEN" to "x"),
      )
    }
    assertTrue(error.message!!.contains("plan"))
    assertTrue(error.message!!.contains("ghost"))
    assertTrue(error.message!!.contains("anthropic-default"))
  }

  @Test
  fun `a profile whose auth_token_env variable is missing refuses naming the variable`() {
    val error = assertFailsWith<UsageError> {
      refuseUnresolvableProfileDirectives(
        directivesByPhase = mapOf("plan" to PhaseModelDirective("opus", profile = "anthropic-default")),
        resolvedAgentIdByPhase = mapOf("plan" to "claude"),
        profiles = profiles,
        environment = emptyMap(),
      )
    }
    assertTrue(error.message!!.contains("ANTHROPIC_AUTH_TOKEN"))
  }

  @Test
  fun `a profile-bearing directive on a non-claude agent refuses at the same seam`() {
    val error = assertFailsWith<UsageError> {
      refuseUnresolvableProfileDirectives(
        directivesByPhase = mapOf("plan" to PhaseModelDirective("gpt", profile = "anthropic-default")),
        resolvedAgentIdByPhase = mapOf("plan" to "codex"),
        profiles = profiles,
        environment = mapOf("ANTHROPIC_AUTH_TOKEN" to "x"),
      )
    }
    assertTrue(error.message!!.contains("codex"))
    assertTrue(error.message!!.contains("claude"))
  }

  @Test
  fun `a claude profile directive with a present token variable passes preflight`() {
    refuseUnresolvableProfileDirectives(
      directivesByPhase = mapOf("plan" to PhaseModelDirective("opus", profile = "anthropic-default")),
      resolvedAgentIdByPhase = mapOf("plan" to "claude"),
      profiles = profiles,
      environment = mapOf("ANTHROPIC_AUTH_TOKEN" to "tok"),
    )
  }

  @Test
  fun `refusal messages never contain the resolved token value`() {
    val errored = assertFailsWith<UsageError> {
      refuseUnresolvableProfileDirectives(
        directivesByPhase = mapOf("plan" to PhaseModelDirective("opus", profile = "anthropic-default")),
        resolvedAgentIdByPhase = mapOf("plan" to "claude"),
        profiles = profiles,
        environment = emptyMap(),
      )
    }
    assertTrue("secret-token-123" !in errored.message!!)
  }
}
