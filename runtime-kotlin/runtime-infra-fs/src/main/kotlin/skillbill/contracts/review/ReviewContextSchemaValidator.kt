package skillbill.contracts.review

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.contracts.LOCALE_STABLE_SCHEMA_CONFIG
import skillbill.contracts.logSchemaLoadFailure
import skillbill.error.InvalidReviewContextSchemaError
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

private const val MAX_REPORTED_VIOLATIONS: Int = 4

private val reviewContextLog: Logger =
  Logger.getLogger("skillbill.contracts.review.ReviewContextSchemaValidator")

object ReviewContextSchemaValidator {
  private val schemas: ReviewContextSchemas by lazy { loadReviewContextSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  fun validate(envelope: Map<String, Any?>, sourceLabel: String) {
    val kind = envelope["kind"] as? String
    validatePayloadAgainst(envelope, sourceLabel, kind, schemas.forKind(kind), mapper)
  }

  fun validateParentPacket(envelope: Map<String, Any?>, sourceLabel: String) =
    validateExpectedKind(envelope, sourceLabel, "parent_packet", schemas, mapper)

  fun validateAssignment(envelope: Map<String, Any?>, sourceLabel: String) =
    validateExpectedKind(envelope, sourceLabel, "assignment", schemas, mapper)

  fun validateLaunch(envelope: Map<String, Any?>, sourceLabel: String) =
    validateExpectedKind(envelope, sourceLabel, "launch", schemas, mapper)

  fun validateIntegrationLaunch(envelope: Map<String, Any?>, sourceLabel: String) =
    validateExpectedKind(envelope, sourceLabel, "integration_launch", schemas, mapper)

  fun validateVerificationLaunch(envelope: Map<String, Any?>, sourceLabel: String) =
    validateExpectedKind(envelope, sourceLabel, "verification_launch", schemas, mapper)

  fun validateAdjudicationLaunch(envelope: Map<String, Any?>, sourceLabel: String) =
    validateExpectedKind(envelope, sourceLabel, "adjudication_launch", schemas, mapper)

  fun validateFindingVerdict(envelope: Map<String, Any?>, sourceLabel: String) =
    validateExpectedKind(envelope, sourceLabel, "finding_verdict", schemas, mapper)

  fun validateSpecIntentProjection(payload: Map<String, Any?>, sourceLabel: String) = validatePayloadAgainst(
    payload,
    sourceLabel,
    "spec_intent_projection",
    schemas.forDefinition("spec_intent_projection"),
    mapper,
  )

  fun assertIdentity(yamlNode: JsonNode) {
    val drift = identityDriftOrNull(yamlNode) ?: return
    throw InvalidReviewContextSchemaError(
      sourceLabel = ReviewContextSchemaPaths.CLASSPATH_RESOURCE,
      reason = drift,
    )
  }
}

private fun identityDriftOrNull(yamlNode: JsonNode): String? {
  val loadedId = yamlNode.path("\$id").asText("")
  if (loadedId != ReviewContextSchemaPaths.EXPECTED_SCHEMA_ID) {
    return "Canonical review context schema identity mismatch: loaded '\$id' is '$loadedId' but expected " +
      "'${ReviewContextSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or shadowed copy is on the classpath."
  }
  val branches = yamlNode.path("\$defs").fields().asSequence()
    .map { (name, node) -> name to node.path("properties").path("contract_version").path("const").asText("") }
    .filter { (_, const) -> const.isNotBlank() }
    .toList()
  if (branches.isEmpty()) {
    return "Canonical review context schema declares no contract_version const; the classpath copy is " +
      "not a governed review-context contract."
  }
  val drifted = branches.filterNot { (_, const) -> const == REVIEW_CONTEXT_CONTRACT_VERSION }
  if (drifted.isEmpty()) return null
  return "Canonical review context schema contract_version.const mismatch for " +
    "${drifted.map { "${it.first}='${it.second}'" }} but the runtime expects " +
    "'$REVIEW_CONTEXT_CONTRACT_VERSION'. The schema on the classpath is out of date relative to the " +
    "running runtime-contracts."
}

private fun validateExpectedKind(
  envelope: Map<String, Any?>,
  sourceLabel: String,
  expectedKind: String,
  schemas: ReviewContextSchemas,
  mapper: ObjectMapper,
) {
  val kind = envelope["kind"]
  if (kind != expectedKind) {
    throw InvalidReviewContextSchemaError(
      sourceLabel = sourceLabel,
      reason = "Expected a '$expectedKind' envelope but the payload declares kind='${kind ?: "<missing>"}'.",
      definitionName = expectedKind,
    )
  }
  validatePayloadAgainst(envelope, sourceLabel, expectedKind, schemas.forKind(expectedKind), mapper)
}

private fun requireMatchingContractVersion(payload: Map<String, Any?>, sourceLabel: String, definitionName: String?) {
  val declared = payload["contract_version"] ?: return
  val declaredText = declared as? String ?: declared.toString()
  if (declaredText == REVIEW_CONTEXT_CONTRACT_VERSION) return
  throw InvalidReviewContextSchemaError(
    sourceLabel = sourceLabel,
    reason = "contract_version mismatch: envelope declares '$declaredText' but the runtime requires " +
      "'$REVIEW_CONTEXT_CONTRACT_VERSION'.",
    definitionName = definitionName,
  )
}

private fun validatePayloadAgainst(
  payload: Map<String, Any?>,
  sourceLabel: String,
  definitionName: String?,
  schema: JsonSchema,
  mapper: ObjectMapper,
) {
  requireMatchingContractVersion(payload, sourceLabel, definitionName)
  val instance: JsonNode = mapper.valueToTree(payload)
  val errors: Set<ValidationMessage> = schema.validate(instance)
  if (errors.isNotEmpty()) {
    val sorted = errors.sortedWith(violationOrdering)
    reviewContextLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, sorted, instance))
    throw InvalidReviewContextSchemaError(
      sourceLabel = sourceLabel,
      reason = formatValidationReason(sorted, instance),
      definitionName = definitionName,
    )
  }
}

private fun buildSchemaDriftLog(sourceLabel: String, sorted: List<ValidationMessage>, instance: JsonNode): String {
  val parts = sorted.take(2).map { error ->
    val location = error.instanceLocation?.toString().orEmpty()
    val fieldPath = dottedFieldPath(location).ifBlank { "<root>" }
    val offendingValue = offendingValue(instance, location)
    if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
  }
  return "Review context envelope failed schema validation: source='$sourceLabel' " +
    "violations=${parts.joinToString(", ")} totalViolations=${sorted.size}"
}

private fun formatValidationReason(sorted: List<ValidationMessage>, instance: JsonNode): String {
  val firstError = sorted.first()
  val offendingValue = offendingValue(instance, firstError.instanceLocation?.toString().orEmpty())
  return buildString {
    append(dottedFieldPath(firstError.instanceLocation?.toString().orEmpty()).ifBlank { "<root>" })
    append(": ")
    append(firstError.message)
    if (offendingValue.isNotBlank()) {
      append(" — offending value: ")
      append(offendingValue)
    }
    sorted.drop(1).take(MAX_REPORTED_VIOLATIONS).forEach { other ->
      append(" | ")
      append(dottedFieldPath(other.instanceLocation?.toString().orEmpty()).ifBlank { "<root>" })
      append(": ")
      append(other.message)
    }
  }
}

private val violationOrdering: Comparator<ValidationMessage> = compareBy(
  { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
  { it.instanceLocation?.toString().orEmpty() },
  { it.message.orEmpty() },
)

private const val MAX_OFFENDING_VALUE_CHARS: Int = 120

private val REDACTED_FIELD_SEGMENTS: Set<String> =
  setOf("excerpt", "content", "reason", "reachability_reason", "rubric", "specialist_contract", "status")

private fun offendingValue(instance: JsonNode, instanceLocation: String): String {
  val dotted = dottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  val segments = dotted.split('.')
  if (segments.any { it in REDACTED_FIELD_SEGMENTS }) return "<redacted>"
  var node: JsonNode = instance
  segments.forEach { segment ->
    if (segment.isBlank()) return@forEach
    node = if (segment.toIntOrNull() != null) node.path(segment.toInt()) else node.path(segment)
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText().take(MAX_OFFENDING_VALUE_CHARS)
    else -> ""
  }
}

private fun dottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}

internal const val REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE: String =
  ReviewContextSchemaPaths.CLASSPATH_RESOURCE

internal const val REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH: String =
  ReviewContextSchemaPaths.REPO_RELATIVE_PATH

private val NESTED_REVIEW_CONTEXT_DEFINITIONS: List<String> = listOf("spec_intent_projection")

internal class ReviewContextSchemas(private val envelope: JsonSchema, private val branches: Map<String, JsonSchema>) {
  fun forKind(kind: String?): JsonSchema = branches[kind] ?: envelope

  fun forDefinition(name: String): JsonSchema = branches[name]
    ?: throw InvalidReviewContextSchemaError(
      sourceLabel = ReviewContextSchemaPaths.CLASSPATH_RESOURCE,
      reason = "Canonical review context schema has no compiled definition '$name'.",
      definitionName = name,
    )
}

private fun logReviewContextSchemaFailure(error: Throwable): Throwable {
  logSchemaLoadFailure(
    reviewContextLog,
    "review context",
    REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
    REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH,
    error,
  )
  return error
}

private fun loadReviewContextSchema(): ReviewContextSchemas {
  var failure: Throwable? = null
  try {
    val yamlText = readReviewContextSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    ReviewContextSchemaValidator.assertIdentity(yamlNode)
    val mapper = ObjectMapper()
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    val envelopeSchema = factory.getSchema(mapper.writeValueAsString(yamlNode), LOCALE_STABLE_SCHEMA_CONFIG)
    val defs = yamlNode.path("\$defs")
    val oneOfNames = yamlNode.path("oneOf").asSequence()
      .map { branch -> branch.path("\$ref").asText("").substringAfterLast('/') }
      .filter { name -> name.isNotBlank() && !defs.path(name).isMissingNode }
      .toList()
    val definitionNames = (oneOfNames + NESTED_REVIEW_CONTEXT_DEFINITIONS)
      .distinct()
    val branches = definitionNames.associateWith { name ->
      if (defs.path(name).isMissingNode) {
        throw InvalidReviewContextSchemaError(
          sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
          reason = "Canonical review context schema is missing definition '$name'.",
          definitionName = name,
        )
      }
      val wrapper = mapper.createObjectNode()
      wrapper.put("\$schema", yamlNode.path("\$schema").asText())
      wrapper.put("\$ref", "#/\$defs/" + name)
      wrapper.set<ObjectNode>("\$defs", defs.deepCopy())
      factory.getSchema(mapper.writeValueAsString(wrapper), LOCALE_STABLE_SCHEMA_CONFIG)
    }
    return ReviewContextSchemas(envelopeSchema, branches)
  } catch (error: InvalidReviewContextSchemaError) {
    failure = logReviewContextSchemaFailure(error)
  } catch (error: IOException) {
    failure = logReviewContextSchemaFailure(error)
  } catch (error: JsonProcessingException) {
    failure = logReviewContextSchemaFailure(error)
  }
  throw failure
}
