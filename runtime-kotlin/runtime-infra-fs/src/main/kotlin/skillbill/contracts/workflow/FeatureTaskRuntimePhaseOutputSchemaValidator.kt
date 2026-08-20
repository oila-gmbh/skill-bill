@file:Suppress("TooGenericExceptionCaught")

package skillbill.contracts.workflow

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import skillbill.contracts.LOCALE_STABLE_SCHEMA_CONFIG
import skillbill.error.InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_AUDIT_REPAIR_RULE_FAMILY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairRuleViolation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemStatus
import skillbill.workflow.taskruntime.model.MAX_AUDIT_REPAIR_REF_LENGTH
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.canonicalAuditIdentifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val featureTaskRuntimePhaseOutputLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputSchemaValidator")

/**
 * Schema validator for feature-task-runtime per-phase output payloads, reached
 * by the runtime only through the domain-owned port. Empty `{}`, malformed
 * input, and any schema violation fail with
 * [InvalidFeatureTaskRuntimePhaseOutputSchemaError].
 */
@Suppress("TooManyFunctions")
object FeatureTaskRuntimePhaseOutputSchemaValidator {
  private val schema: JsonSchema by lazy { loadFeatureTaskRuntimePhaseOutputSchema() }
  private val auditRepairSchema: JsonSchema by lazy { loadAuditRepairPlanSchema() }
  private val mapper: ObjectMapper by lazy { ObjectMapper() }
  private val yamlMapper: YAMLMapper by lazy {
    YAMLMapper(YAMLFactory().apply { enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) })
  }
  private val mapType = object : TypeReference<Map<String, Any?>>() {}

  fun validate(phaseOutput: Map<String, Any?>, sourceLabel: String) {
    val instance: JsonNode = mapper.valueToTree(phaseOutput)
    val errors: Set<ValidationMessage> = schema.validate(instance)
    if (errors.isNotEmpty()) {
      featureTaskRuntimePhaseOutputLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors))
      val reasons = formatViolationReasons(errors.sortedWith(violationOrdering), instance)
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = reasons.valueBearing,
        payloadFreeReason = reasons.payloadFree,
      )
    }
    validateAuditRepairPlan(instance, sourceLabel)
    val phaseId = phaseOutput["phase_id"] as? String
    if (phaseId != sourceLabel) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "phase_id must match the executing phase '$sourceLabel' but was '${phaseId.orEmpty()}'.",
        // sourceLabel is the runtime's own phase id, so naming the expectation carries no response content.
        payloadFreeReason = "phase_id must match the executing phase '$sourceLabel'.",
        failureCode = "phase_id_mismatch",
      )
    }
  }

  private fun validateAuditRepairPlan(instance: JsonNode, sourceLabel: String) {
    if (sourceLabel != "audit") return
    val producedOutputs = instance.path("produced_outputs")
    val unmetCriteria = producedOutputs.path("unmet_criteria")
    if (!unmetCriteria.isArray || unmetCriteria.isEmpty) return
    val plan = producedOutputs.path("audit_repair_plan")
    try {
      requireAuditRepairPlanSchema(plan, sourceLabel)
      decodeAuditRepairPlan(plan).requireExactCriterionCoverage(
        unmetCriteria.map {
          it.path("acceptance_criterion_ref").asText()
        },
      )
    } catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "produced_outputs.audit_repair_plan: ${error.reason}",
        cause = error,
        payloadFreeReason = error.payloadFreeReason?.let { "produced_outputs.audit_repair_plan: $it" },
      )
    } catch (error: IllegalArgumentException) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "produced_outputs.audit_repair_plan: ${error.message.orEmpty()}",
        cause = error,
        payloadFreeReason = "produced_outputs.audit_repair_plan: ${auditRepairRuleRestatement(error)}",
        failureCode = "semantic_invalid",
      )
    }
  }

  private fun requireAuditRepairPlanSchema(plan: JsonNode, sourceLabel: String) {
    val errors = auditRepairSchema.validate(plan)
    if (errors.isEmpty()) return
    val reasons = formatViolationReasons(errors.sortedWith(violationOrdering), plan)
    throw InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(
      sourceLabel = sourceLabel,
      reason = reasons.valueBearing,
      payloadFreeReason = reasons.payloadFree,
    )
  }

  // A typed rule violation carries its own value-free restatement of the rule it enforces; anything else
  // reaching this seam is restated as the rule family so the retry prompt still names the constraint
  // rather than only the pointer it failed at.
  private fun auditRepairRuleRestatement(error: IllegalArgumentException): String =
    (error as? FeatureTaskRuntimeAuditRepairRuleViolation)?.payloadFreeMessage
      ?: FEATURE_TASK_RUNTIME_AUDIT_REPAIR_RULE_FAMILY

  private fun decodeAuditRepairPlan(node: JsonNode): FeatureTaskRuntimeAuditRepairPlan =
    FeatureTaskRuntimeAuditRepairPlan(
      contractVersion = node.path("contract_version").asText(),
      gaps = node.path("gaps").map { gap ->
        FeatureTaskRuntimeAuditGap(
          gapId = canonicalAuditIdentifier(gap.path("gap_id").asText()),
          acceptanceCriterionRef = gap.path("acceptance_criterion_ref").asText(),
          acceptanceCriterionText = gap.path("acceptance_criterion_text").asText(),
          failureEvidence = gap.path("failure_evidence").let { evidence ->
            FeatureTaskRuntimeEvidence(
              observation = FeatureTaskRuntimeEvidence.Observation.valueOf(
                evidence.path("observation").asText().uppercase(),
              ),
              artifactRef = evidence.path("artifact_ref").asText(),
              checkRef = evidence.path("check_ref").asText(),
            )
          },
          diagnosis = gap.path("diagnosis").asText(),
          affectedBoundary = gap.path("affected_boundary").asText(),
          repairItems = gap.path("repair_items").map { item ->
            FeatureTaskRuntimeRepairItem(
              repairItemId = canonicalAuditIdentifier(item.path("repair_item_id").asText()),
              intendedOutcome = item.path("intended_outcome").asText(),
              implementationActions = item.path("implementation_actions").map(JsonNode::asText),
              affectedPathsOrSymbols = item.path("affected_paths_or_symbols").map(JsonNode::asText),
              requiredVerification = item.path("required_verification").map(JsonNode::asText),
              dependsOn = item.path("depends_on").map { canonicalAuditIdentifier(it.asText()) },
              status = FeatureTaskRuntimeRepairItemStatus.PENDING,
            )
          },
        )
      },
    )

  fun validatePhaseOutputText(phaseOutputText: String, sourceLabel: String) {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
    validateExpandedAuditRepairPlan(expandCompactAuditOutput(parsed, sourceLabel), sourceLabel)
  }

  fun validateAndReadPhaseOutput(phaseOutputText: String, sourceLabel: String): Map<String, Any?> {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
    return expandCompactAuditOutput(parsed, sourceLabel).also {
      validateExpandedAuditRepairPlan(it, sourceLabel)
    }
  }

  fun normalizePhaseOutput(phaseOutputText: String, sourceLabel: String): NormalizedFeatureTaskRuntimePhaseOutput {
    val node = readPhaseOutputObjectNode(phaseOutputText, sourceLabel)
    val parsed = phaseOutputObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
    val expanded = expandCompactAuditOutput(parsed, sourceLabel)
    validateExpandedAuditRepairPlan(expanded, sourceLabel)
    return NormalizedFeatureTaskRuntimePhaseOutput(
      canonicalJson = mapper.writeValueAsString(parsed),
      envelope = expanded,
    )
  }

  private fun validateExpandedAuditRepairPlan(phaseOutput: Map<String, Any?>, sourceLabel: String) {
    validateAuditRepairPlan(mapper.valueToTree(phaseOutput), sourceLabel)
  }

  private fun expandCompactAuditOutput(phaseOutput: Map<String, Any?>, sourceLabel: String): Map<String, Any?> {
    if (sourceLabel != "audit") return phaseOutput
    val produced = phaseOutput["produced_outputs"] as? Map<*, *> ?: return phaseOutput
    val compactGaps = produced["gaps"] as? List<*> ?: return phaseOutput
    val expandedGaps = compactGaps
      .groupBy { compactGapCriterion(it) }
      .map { (_, criterionGaps) -> compactGapsToRepairGap(criterionGaps) }
    val normalizedProduced = linkedMapOf<String, Any?>().apply {
      produced.forEach { (key, value) ->
        if (key is String && key != "gaps") put(key, value)
      }
      put(
        "unmet_criteria",
        compactGaps
          .groupBy { compactGapCriterion(it) }
          .map { (_, criterionGaps) -> compactGapsToUnmetCriterion(criterionGaps) },
      )
      if (expandedGaps.isNotEmpty()) {
        put(
          "audit_repair_plan",
          mapOf(
            "contract_version" to FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CONTRACT_VERSION,
            "gaps" to expandedGaps,
          ),
        )
      }
    }
    return LinkedHashMap(phaseOutput).apply { put("produced_outputs", normalizedProduced) }
  }

  private fun compactGapCriterion(value: Any?): String {
    @Suppress("UNCHECKED_CAST")
    val gap = value as Map<String, Any?>
    return gap.getValue("criterion") as String
  }

  private fun compactGapsToRepairGap(values: List<Any?>): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val gaps = values.map { it as Map<String, Any?> }
    val first = gaps.first()
    val criterion = first.getValue("criterion") as String
    val issue = first.getValue("issue") as String
    val gapId = "${criterion.lowercase()}-gap-1"
    val sitePointers = gaps.map(::compactGapSitePointer)
    return mapOf(
      "gap_id" to gapId,
      "acceptance_criterion_ref" to criterion,
      "acceptance_criterion_text" to issue,
      "failure_evidence" to mapOf(
        "observation" to "required_behavior_absent",
        "artifact_ref" to boundedJoinedArtifactRef(criterion, sitePointers),
        "check_ref" to criterion,
      ),
      "diagnosis" to issue,
      "affected_boundary" to gaps.joinToString(" | ") { it.getValue("location") as String },
      "repair_items" to gaps.mapIndexed { index, gap ->
        val itemLocation = gap.getValue("location") as String
        val itemFix = gap.getValue("fix") as String
        mapOf(
          "repair_item_id" to "$gapId-item-${index + 1}",
          "intended_outcome" to itemFix,
          "implementation_actions" to listOf(itemFix),
          "affected_paths_or_symbols" to listOf(compactGapItemArtifactRef(gap)),
          "required_verification" to listOf("Verify $criterion at $itemLocation"),
          "depends_on" to emptyList<String>(),
          "status" to "pending",
        )
      },
    )
  }

  private fun compactGapSitePointer(gap: Map<String, Any?>): String {
    val gapLocation = gap.getValue("location") as String
    return listOfNotNull(gap["file"] as? String, gapLocation)
      .joinToString("-")
      .replace('/', '-')
      .replace(':', '-')
  }

  private fun compactGapItemArtifactRef(gap: Map<String, Any?>): String {
    val itemLocation = gap.getValue("location") as String
    return (gap["file"] as? String)?.let { "$it:$itemLocation" } ?: itemLocation
  }

  private fun boundedJoinedArtifactRef(criterion: String, sitePointers: List<String>): String {
    val joined = "$criterion:" + sitePointers.joinToString("--")
    if (joined.length <= MAX_AUDIT_REPAIR_REF_LENGTH) return joined
    return "$criterion:" + sitePointers.first()
  }

  private fun compactGapsToUnmetCriterion(values: List<Any?>): Map<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val gaps = values.map { it as Map<String, Any?> }
    val first = gaps.first()
    return mapOf(
      "acceptance_criterion_ref" to first.getValue("criterion"),
      "severity" to if (gaps.any { it["severity"] == "blocker" }) "blocker" else "major",
      "message" to gaps.joinToString("; ") { it.getValue("issue") as String },
    )
  }

  // Agents launched via `claude --print` (and peers) emit a final message, not a bare payload:
  // the JSON object is commonly wrapped in a ``` fence or trailed by a closing remark. Candidate
  // order is most-specific-first, and that positional precedence decides which envelope is the
  // agent's answer: schema validity never promotes an earlier restated envelope over the final one,
  // otherwise a stale `satisfied` draft could win over the real `gaps_found` answer and advance the
  // workflow past unmet criteria. Falling back to the raw text keeps genuinely payload-less or
  // malformed output on its precise existing error.
  private fun readPhaseOutputObjectNode(phaseOutputText: String, sourceLabel: String): JsonNode {
    val parsedCandidates = phaseOutputObjectCandidates(phaseOutputText).mapNotNull(::tryParseObjectNode)
    val envelopeCandidates = parsedCandidates.filter { candidate ->
      candidate.path("phase_id").asText("") == sourceLabel
    }
    val distinctValidEnvelopes = envelopeCandidates
      .filter { candidate -> schema.validate(candidate).isEmpty() }
      .distinctBy(::canonicalCandidateKey)
    if (distinctValidEnvelopes.size > 1) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "Phase output contains multiple conflicting schema-valid envelopes.",
        payloadFreeReason = "Phase output contains multiple conflicting schema-valid envelopes.",
      )
    }
    envelopeCandidates.firstOrNull()?.let { return it }
    parsedCandidates.firstOrNull()?.let { return it }
    return parseObjectNodeStrict(phaseOutputText.trim(), sourceLabel)
  }

  // Key order carries no meaning in the envelope, so one restated envelope must not read as two.
  private fun canonicalCandidateKey(candidate: JsonNode): String =
    mapper.writeValueAsString(canonicalizeCandidate(candidate))

  private fun canonicalizeCandidate(node: JsonNode): JsonNode = when {
    node.isObject -> mapper.createObjectNode().apply {
      node.fieldNames().asSequence().sorted().forEach { field ->
        set<JsonNode>(field, canonicalizeCandidate(node.path(field)))
      }
    }
    node.isArray -> mapper.createArrayNode().apply {
      node.forEach { element -> add(canonicalizeCandidate(element)) }
    }
    else -> node
  }

  private fun tryParseObjectNode(candidate: String): JsonNode? = try {
    yamlMapper.readTree(candidate)?.takeIf(JsonNode::isObject)
  } catch (error: JsonProcessingException) {
    featureTaskRuntimePhaseOutputLog.log(
      Level.FINE,
      "Phase-output candidate did not parse; trying the next one.",
      error,
    )
    null
  }

  private fun parseObjectNodeStrict(text: String, sourceLabel: String): JsonNode {
    val node =
      try {
        yamlMapper.readTree(text)
      } catch (error: JsonProcessingException) {
        throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = sourceLabel,
          reason = "Phase output is malformed: ${error.originalMessage.orEmpty()}",
          cause = error,
          // Jackson's originalMessage quotes the offending token, so only the prefix survives here. The
          // prompt composer keys its unparseable-root correction on that prefix.
          payloadFreeReason = "Phase output is malformed: it is not parseable as a single JSON object.",
          failureCode = "malformed",
        )
      }
    if (node == null || !node.isObject) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = sourceLabel,
        reason = "<root> must be an object.",
        payloadFreeReason = "<root> must be an object.",
        failureCode = "root_not_object",
      )
    }
    return node
  }

  private fun phaseOutputObjectNodeToMap(node: JsonNode, sourceLabel: String): Map<String, Any?> = try {
    mapper.convertValue(node, mapType)
  } catch (error: IllegalArgumentException) {
    throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
      sourceLabel = sourceLabel,
      reason = "Phase output root object cannot be converted to a string-keyed map: ${error.message.orEmpty()}",
      cause = error,
      payloadFreeReason = "Phase output root object cannot be converted to a string-keyed map.",
    )
  }

  fun assertIdentity(yamlNode: JsonNode) {
    val loadedId = yamlNode.path("\$id").asText("")
    if (loadedId != FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE,
        reason = "Canonical feature-task-runtime phase output schema identity mismatch: loaded '\$id' is " +
          "'$loadedId' but expected '${FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID}'. A stale or " +
          "shadowed copy of the schema is on the classpath.",
      )
    }
    val loadedConst = yamlNode.path("properties").path("contract_version").path("const").asText("")
    if (loadedConst != FEATURE_TASK_RUNTIME_CONTRACT_VERSION) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE,
        reason = "Canonical feature-task-runtime phase output schema contract_version.const mismatch: loaded " +
          "'$loadedConst' but the runtime expects '$FEATURE_TASK_RUNTIME_CONTRACT_VERSION'. The schema on the " +
          "classpath is out of date relative to the running runtime-contracts.",
      )
    }
  }

  /**
   * Operator/warning log only: field paths and constraint metadata. Never includes extracted instance
   * values — those belong solely in the private value-bearing rejection reason.
   */
  private fun buildSchemaDriftLog(sourceLabel: String, errors: Set<ValidationMessage>): String {
    val parts = errors.sortedWith(violationOrdering).take(2).map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = featureTaskRuntimePhaseOutputDottedFieldPath(location).ifBlank { "<root>" }
      val constraint = error.message.orEmpty().trim()
      if (constraint.isNotEmpty()) "$fieldPath: $constraint" else fieldPath
    }
    return "Feature-task-runtime phase output failed schema validation: source='$sourceLabel' " +
      "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
  }

  /**
   * Both renderings of one violation list. [valueBearing] is the long-standing text, offending values
   * included, and belongs only in a private diagnostic row or a local log; [payloadFree] is the same rule
   * and field-path content with every offending value omitted, and is the only variant a retry prompt or
   * operator surface may carry. They are emitted from a single traversal so the rule and path content of
   * the two can never drift apart.
   */
  private data class PhaseOutputViolationReasons(val valueBearing: String, val payloadFree: String)

  private fun formatViolationReasons(
    sorted: List<ValidationMessage>,
    instance: JsonNode,
  ): PhaseOutputViolationReasons {
    val violations = sorted.map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = featureTaskRuntimePhaseOutputDottedFieldPath(location).ifBlank { "<root>" }
      val head = "$fieldPath: ${error.message}"
      head to extractFeatureTaskRuntimePhaseOutputOffendingValue(instance, location)
    }
    fun render(includeOffendingValues: Boolean): String =
      violations.joinToString(separator = " | ") { (head, offendingValue) ->
        if (includeOffendingValues && offendingValue.isNotBlank()) {
          "$head — offending value: $offendingValue"
        } else {
          head
        }
      }
    return PhaseOutputViolationReasons(valueBearing = render(true), payloadFree = render(false))
  }

  private val violationOrdering: Comparator<ValidationMessage> = compareBy(
    { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
    { it.instanceLocation?.toString().orEmpty() },
    { it.message.orEmpty() },
  )
}

private fun loadAuditRepairPlanSchema(): JsonSchema = try {
  val resource = FeatureTaskRuntimeAuditRepairPlanSchemaPaths.CLASSPATH_RESOURCE
  val repoPath = FeatureTaskRuntimeAuditRepairPlanSchemaPaths.REPO_RELATIVE_PATH
  val text = FeatureTaskRuntimePhaseOutputSchemaValidator::class.java.classLoader
    .getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) }
    ?: walkForSchemaFile(Path.of("").toAbsolutePath(), repoPath)?.let(Files::readString)
    ?: throw InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(
      sourceLabel = resource,
      reason = "Canonical audit repair plan schema is missing from '$resource' and '$repoPath'.",
    )
  loadAuditRepairPlanSchemaText(text, resource)
} catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
  throw error
} catch (error: Exception) {
  throw InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(
    sourceLabel = FeatureTaskRuntimeAuditRepairPlanSchemaPaths.CLASSPATH_RESOURCE,
    reason = error.message ?: error::class.simpleName.orEmpty(),
    cause = error,
  )
}

internal fun loadAuditRepairPlanSchemaText(text: String, sourceLabel: String): JsonSchema = try {
  val node = YAMLMapper().readTree(text)
    ?: throw IllegalArgumentException("Canonical audit repair plan schema is empty.")
  val loadedId = node.path("\$id").asText("")
  val loadedVersion = node.path("properties").path("contract_version").path("const").asText("")
  require(loadedId == FeatureTaskRuntimeAuditRepairPlanSchemaPaths.EXPECTED_SCHEMA_ID) {
    "Canonical audit repair plan schema identity mismatch: loaded '$loadedId' but expected " +
      "'${FeatureTaskRuntimeAuditRepairPlanSchemaPaths.EXPECTED_SCHEMA_ID}'."
  }
  require(loadedVersion == FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CONTRACT_VERSION) {
    "Canonical audit repair plan schema contract version mismatch: loaded '$loadedVersion' but expected " +
      "'$FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CONTRACT_VERSION'."
  }
  JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    .getSchema(ObjectMapper().writeValueAsString(node), LOCALE_STABLE_SCHEMA_CONFIG)
} catch (error: InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError) {
  throw error
} catch (error: Exception) {
  throw InvalidFeatureTaskRuntimeAuditRepairPlanSchemaError(
    sourceLabel = sourceLabel,
    reason = error.message ?: error::class.simpleName.orEmpty(),
    cause = error,
  )
}

private fun walkForSchemaFile(hint: Path, repoRelativePath: String): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(repoRelativePath)
    if (Files.isRegularFile(candidate)) return candidate
    current = current.parent
  }
  return null
}

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.REPO_RELATIVE_PATH

private fun loadFeatureTaskRuntimePhaseOutputSchema(): JsonSchema {
  try {
    val yamlText = readFeatureTaskRuntimePhaseOutputSchemaText()
    val yamlNode = YAMLMapper().readTree(yamlText)
    FeatureTaskRuntimePhaseOutputSchemaValidator.assertIdentity(yamlNode)
    val jsonText = ObjectMapper().writeValueAsString(yamlNode)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    return factory.getSchema(jsonText, LOCALE_STABLE_SCHEMA_CONFIG)
  } catch (error: Throwable) {
    featureTaskRuntimePhaseOutputLog.log(
      Level.SEVERE,
      "Failed to load canonical feature-task-runtime phase output schema: " +
        "classpath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE' " +
        "repoRelativePath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH' " +
        "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
      error,
    )
    throw error
  }
}

private fun readFeatureTaskRuntimePhaseOutputSchemaText(): String {
  FeatureTaskRuntimePhaseOutputSchemaValidator::class.java.classLoader
    .getResourceAsStream(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE)
    ?.use { return it.readBytes().toString(Charsets.UTF_8) }

  val walkAnchor: Path = Path.of("").toAbsolutePath()
  val resolved = walkForFeatureTaskRuntimePhaseOutputSchemaFile(walkAnchor)
  if (resolved != null) {
    return Files.readString(resolved)
  }
  throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
    sourceLabel = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
    reason = "Canonical feature-task-runtime phase output schema is missing. Expected to find it on the JVM " +
      "classpath at '$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE' or on disk under " +
      "'$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH' walked up from: $walkAnchor.",
  )
}

private val FENCED_BLOCK = Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

// Ordered, de-duplicated candidates to try as the phase-output object: fenced blocks last-first
// (the final fence is the most likely answer), then each balanced top-level `{...}` region last-first
// (recovers the real object when an example object or a brace-bearing prose table precedes it), then a
// first-`{`-to-last-`}` slice as a structural fallback, then the raw text so clean JSON/YAML and genuine
// failures are unchanged.
private fun phaseOutputObjectCandidates(raw: String): List<String> {
  val trimmed = raw.trim()
  return buildList {
    FENCED_BLOCK.findAll(trimmed).map { it.groupValues[1].trim() }.toList().asReversed().forEach(::add)
    balancedTopLevelObjectSpans(trimmed).asReversed().forEach(::add)
    val open = trimmed.indexOf('{')
    val close = trimmed.lastIndexOf('}')
    if (open in 0 until close) {
      add(trimmed.substring(open, close + 1))
    }
    add(trimmed)
  }.filter(String::isNotBlank).distinct()
}

// Each balanced top-level `{...}` region in source order, scanned with JSON string-literal awareness so
// braces inside quoted values never throw off the depth count. The naive first-`{`-to-last-`}` slice
// spans across two disjoint objects (an example then the real answer) or across a prose table peppered
// with braces and parses as neither; isolating each balanced object lets the caller try the final one,
// which is the agent's real answer, before falling back.
private fun balancedTopLevelObjectSpans(text: String): List<String> {
  val scanner = TopLevelObjectScanner(text)
  return text.indices.mapNotNull(scanner::consume)
}

// Single-pass scanner emitting each balanced top-level `{...}` substring as it closes. Splitting the
// string-literal and structural state transitions into their own small steps keeps each branch shallow
// and the whole walk free of loop jumps.
private class TopLevelObjectScanner(private val text: String) {
  private var depth = 0
  private var start = -1
  private var inString = false
  private var escaped = false

  fun consume(index: Int): String? {
    val ch = text[index]
    if (inString) {
      advanceStringState(ch)
      return null
    }
    return advanceStructuralState(ch, index)
  }

  private fun advanceStringState(ch: Char) {
    if (escaped) {
      escaped = false
      return
    }
    when (ch) {
      '\\' -> escaped = true
      '"' -> inString = false
    }
  }

  private fun advanceStructuralState(ch: Char, index: Int): String? {
    when (ch) {
      '"' -> inString = true
      '{' -> openObject(index)
      '}' -> return closeObject(index)
    }
    return null
  }

  private fun openObject(index: Int) {
    if (depth == 0) start = index
    depth += 1
  }

  private fun closeObject(index: Int): String? {
    if (depth == 0) return null
    depth -= 1
    if (depth != 0 || start < 0) return null
    val span = text.substring(start, index + 1)
    start = -1
    return span
  }
}

fun featureTaskRuntimePhaseOutputDottedFieldPath(instanceLocation: String): String = when {
  instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
  instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
  instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
  else -> instanceLocation.trimStart('/').replace('/', '.')
}

fun extractFeatureTaskRuntimePhaseOutputOffendingValue(instance: JsonNode, instanceLocation: String): String {
  val dotted = featureTaskRuntimePhaseOutputDottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  var node: JsonNode = instance
  dotted.split('.').forEach { rawSegment ->
    if (rawSegment.isBlank()) return@forEach
    val arrayMatch = Regex("^([^\\[]*)\\[(\\d+)]$").matchEntire(rawSegment)
    when {
      arrayMatch != null -> {
        val (keyPart, indexPart) = arrayMatch.destructured
        if (keyPart.isNotBlank()) {
          node = node.path(keyPart)
        }
        node = node.path(indexPart.toInt())
      }
      node.isArray && rawSegment.toIntOrNull() != null -> {
        node = node.path(rawSegment.toInt())
      }
      else -> {
        node = node.path(rawSegment)
      }
    }
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> ""
  }
}

private fun walkForFeatureTaskRuntimePhaseOutputSchemaFile(hint: Path): Path? {
  var current: Path? = hint.toAbsolutePath().normalize()
  while (current != null) {
    val candidate = current.resolve(FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH)
    if (Files.isRegularFile(candidate)) {
      return candidate
    }
    current = current.parent
  }
  return null
}
