package skillbill.mcp

import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy
import skillbill.mcp.core.McpToolRegistry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `repository_identity` argument is validated against the
 * `repo-root-realpath-v1:` prefix, but that requirement is otherwise
 * undocumented on the wire: a caller sees only the MCP input schema. These
 * tests pin the schema so the prefix cannot silently stop being advertised.
 */
class RepositoryIdentitySchemaAdvertisementTest {
  @Test
  fun `every tool taking repository_identity advertises the required prefix`() {
    val tools = McpToolRegistry.tools.filter { repositoryIdentitySchemaOf(it.inputSchema) != null }
    assertTrue(tools.isNotEmpty(), "No MCP tool declares a repository_identity property.")

    tools.forEach { tool ->
      val schema = requireNotNull(repositoryIdentitySchemaOf(tool.inputSchema))
      val description = schema["description"] as? String
      assertTrue(
        description != null,
        "Tool '${tool.name}' exposes repository_identity without a description; " +
          "callers cannot discover the required prefix.",
      )
      assertContains(description, FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX)
      assertEquals(
        "^${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/",
        schema["pattern"],
        "Tool '${tool.name}' repository_identity pattern drifted from the policy prefix.",
      )
    }
  }

  @Test
  fun `the advertised pattern accepts canonical identities and rejects bare paths`() {
    val schema = requireNotNull(
      McpToolRegistry.toolNamed("feature_task_continuation_lookup")
        ?.inputSchema
        ?.let(::repositoryIdentitySchemaOf),
    )
    val pattern = Regex(schema["pattern"] as String)
    val prefix = FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX

    assertTrue(pattern.containsMatchIn("$prefix/home/me/projects/app"))
    assertTrue(!pattern.containsMatchIn("/home/me/projects/app"))
    assertTrue(!pattern.containsMatchIn("${prefix}home/me/projects/app"))
  }

  @Suppress("UNCHECKED_CAST")
  private fun repositoryIdentitySchemaOf(inputSchema: Map<String, Any?>): Map<String, Any?>? =
    (inputSchema["properties"] as? Map<String, Map<String, Any?>>)?.get("repository_identity")
}
