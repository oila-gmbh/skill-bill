package skillbill.mcp

import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.mcp.core.McpRuntimeContext
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class McpRepositoryRootResolutionParityTest {
  @Test
  fun `mcp runtime resolves enclosing repository root when repositoryRoot is a nested subdirectory`() {
    val fixtureRoot = Files.createTempDirectory("skillbill-mcp-nested-root")
    Files.createDirectory(fixtureRoot.resolve(".git"))
    val nested = fixtureRoot.resolve("nested/subdir")
    nested.toFile().mkdirs()
    val configPath = fixtureRoot.resolve("config.json")
    Files.writeString(
      configPath,
      """
      {
        "install_id": "test-install-id",
        "telemetry": {
          "level": "off",
          "proxy_url": "",
          "batch_size": 50
        }
      }
      """.trimIndent() + "\n",
    )
    val context = McpRuntimeContext(
      environment = mapOf(
        "SKILL_BILL_REVIEW_DB" to fixtureRoot.resolve("metrics.db").toString(),
        CONFIG_ENVIRONMENT_KEY to configPath.toString(),
      ),
      userHome = fixtureRoot,
      repositoryRoot = nested,
    )
    val resolved = RuntimeComponent::class.create(context.toRuntimeContext()).resolvedEnvironmentContext.repositoryRoot
    assertEquals(
      fixtureRoot.toRealPath(),
      resolved.toRealPath(),
      "an MCP tool that keys work on the invocation directory instead of the injected root " +
        "writes telemetry and workflow rows under a different repository identity than the runtime beneath it",
    )
  }
}
