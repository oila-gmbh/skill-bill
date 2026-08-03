package skillbill.contracts.review

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.InvalidReviewLifecycleEvidenceSchemaError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewLifecycleEvidenceSchemaContractVersionTest {
  @Test fun `schema identity and branches pin the runtime contract`() {
    val node = YAMLMapper().readTree(Files.readString(repoSchema()))
    assertEquals(ReviewLifecycleEvidenceSchemaPaths.EXPECTED_SCHEMA_ID, node.path("\$id").asText())
    node.path("oneOf").forEach { branch ->
      val name = branch.path("\$ref").asText().substringAfterLast('/')
      assertEquals(
        REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION,
        node.path("\$defs").path(name).path("properties").path("contract_version").path("const").asText(),
      )
    }
  }

  @Test fun `bounded lifecycle package is accepted and raw content fields are rejected`() {
    ReviewLifecycleEvidenceSchemaValidator.validate(validPackage(), "fixture")
    val leaking = validPackage() + ("prompt" to "not allowed")
    assertFailsWith<InvalidReviewLifecycleEvidenceSchemaError> {
      ReviewLifecycleEvidenceSchemaValidator.validate(leaking, "leaking-fixture")
    }
    val heartbeatOnly = mapOf(
      "contract_version" to REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION,
      "kind" to "review_lifecycle_evidence",
      "review_id" to "review",
      "packet_digest" to "a".repeat(64),
      "events" to listOf(
        mapOf(
          "contract_version" to REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION,
          "kind" to "lifecycle_event",
          "event_id" to "progress",
          "review_id" to "review",
          "sequence" to 1,
          "occurred_at" to "2026-08-02T00:00:00Z",
          "component" to "worker",
          "event_kind" to "worker_progress",
          "packet_digest" to "a".repeat(64),
          "worker_id" to "worker",
          "provider_id" to "codex",
          "attempt" to 1,
          "assignment_digest" to "b".repeat(64),
          "routed_area" to "architecture",
        ),
      ),
    )
    assertFailsWith<InvalidReviewLifecycleEvidenceSchemaError> {
      ReviewLifecycleEvidenceSchemaValidator.validate(heartbeatOnly, "heartbeat-only")
    }
  }

  @Test fun `classpath schema is byte identical to repository schema`() {
    val bundled = javaClass.classLoader
      .getResourceAsStream(ReviewLifecycleEvidenceSchemaPaths.CLASSPATH_RESOURCE)?.use { it.readBytes() }
    requireNotNull(bundled)
    assertTrue(bundled.contentEquals(Files.readAllBytes(repoSchema())))
  }

  private fun validPackage(): Map<String, Any?> = mapOf(
    "contract_version" to REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION,
    "kind" to "review_lifecycle_evidence",
    "review_id" to "review",
    "packet_digest" to "a".repeat(64),
    "events" to emptyList<Map<String, Any?>>(),
  )

  private fun repoSchema(): Path {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val candidate = current.resolve(ReviewLifecycleEvidenceSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(candidate)) return candidate
      current = current.parent
    }
    error("Review lifecycle evidence schema not found.")
  }
}
