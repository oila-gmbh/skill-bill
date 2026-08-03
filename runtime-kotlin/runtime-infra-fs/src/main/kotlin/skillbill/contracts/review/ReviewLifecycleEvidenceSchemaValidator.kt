@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.error.InvalidReviewLifecycleEvidenceSchemaError
import java.nio.file.Files
import java.nio.file.Path

/** Parse-seam validator for bounded lifecycle evidence; it never accepts raw review content. */
object ReviewLifecycleEvidenceSchemaValidator {
  private val schema: JsonSchema by lazy { loadSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(payload: Map<String, Any?>, sourceLabel: String) {
    val errors: Set<ValidationMessage> = schema.validate(mapper.valueToTree(payload))
    if (errors.isEmpty()) return
    val first = errors.sortedBy { it.instanceLocation?.toString().orEmpty() }.first()
    throw InvalidReviewLifecycleEvidenceSchemaError(
      sourceLabel,
      "${first.instanceLocation?.toString().orEmpty().ifBlank { "<root>" }}: ${first.message}",
    )
  }

  fun assertIdentity(yamlNode: JsonNode) {
    if (yamlNode.path("\$id").asText() != ReviewLifecycleEvidenceSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidReviewLifecycleEvidenceSchemaError(
        ReviewLifecycleEvidenceSchemaPaths.CLASSPATH_RESOURCE,
        "Schema identity does not match ${ReviewLifecycleEvidenceSchemaPaths.EXPECTED_SCHEMA_ID}.",
      )
    }
    val versions = yamlNode.path("oneOf").map { branch ->
      branch.path("\$ref").asText().substringAfterLast('/').let { name ->
        yamlNode.path("\$defs").path(name).path("properties").path("contract_version").path("const").asText()
      }
    }
    if (versions.isEmpty() || versions.any { it != REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION }) {
      throw InvalidReviewLifecycleEvidenceSchemaError(
        ReviewLifecycleEvidenceSchemaPaths.CLASSPATH_RESOURCE,
        "Schema contract version is not ${REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION}.",
      )
    }
  }

  private fun loadSchema(): JsonSchema {
    val text = ReviewLifecycleEvidenceSchemaValidator::class.java.classLoader
      .getResourceAsStream(ReviewLifecycleEvidenceSchemaPaths.CLASSPATH_RESOURCE)
      ?.use { it.readBytes().toString(Charsets.UTF_8) }
      ?: findRepoSchema()
    val yamlNode = YAMLMapper().readTree(text)
    assertIdentity(yamlNode)
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
      .getSchema(ObjectMapper().writeValueAsString(yamlNode))
  }

  private fun findRepoSchema(): String {
    var current: Path? = Path.of("").toAbsolutePath().normalize()
    while (current != null) {
      val path = current.resolve(ReviewLifecycleEvidenceSchemaPaths.REPO_RELATIVE_PATH)
      if (Files.isRegularFile(path)) return Files.readString(path)
      current = current.parent
    }
    throw InvalidReviewLifecycleEvidenceSchemaError(
      ReviewLifecycleEvidenceSchemaPaths.CLASSPATH_RESOURCE,
      "Canonical lifecycle evidence schema is missing.",
    )
  }
}
