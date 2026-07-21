package skillbill.contracts.review

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewContextSchemaContractVersionTest {
  private val repoSchema: Path = repoSchemaPath()

  @Test fun `schema identity matches the runtime expectation`() {
    val node = YAMLMapper().readTree(Files.readString(repoSchema))
    assertEquals(ReviewContextSchemaPaths.EXPECTED_SCHEMA_ID, node.path("\$id").asText())
  }

  @Test fun `every envelope branch pins the runtime contract version`() {
    val node = YAMLMapper().readTree(Files.readString(repoSchema))
    val consts = node.path("\$defs").fields().asSequence()
      .map { (name, branch) -> name to branch.path("properties").path("contract_version").path("const").asText("") }
      .filter { (_, const) -> const.isNotBlank() }
      .toList()
    assertTrue(consts.size >= 5, "Expected every governed envelope branch to pin contract_version: $consts")
    consts.forEach { (name, const) ->
      assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, const, "Branch '$name' pins a stale contract_version")
    }
  }

  @Test fun `bundled classpath schema is byte identical to the repository schema`() {
    val bundled = javaClass.classLoader.getResourceAsStream(ReviewContextSchemaPaths.CLASSPATH_RESOURCE)
      ?.use { it.readBytes() }
    requireNotNull(bundled) { "Canonical review-context schema is not bundled on the test classpath." }
    assertTrue(
      bundled.contentEquals(Files.readAllBytes(repoSchema)),
      "Bundled review-context schema drifted from ${ReviewContextSchemaPaths.REPO_RELATIVE_PATH}.",
    )
  }

  private fun repoSchemaPath(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(ReviewContextSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("Canonical review-context schema not found under ${ReviewContextSchemaPaths.REPO_RELATIVE_PATH}.")
  }
}
