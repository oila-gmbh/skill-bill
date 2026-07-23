package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.NoopFeatureTaskRuntimePlanningProjectionValidator

/**
 * Typed handoff-projection primitives. Together they replace the generic upstream-payload map with a
 * four-part boundary: private evidence stays in the durable phase record, the consumer projection is
 * the only prompt-visible view of it, repository-derived context arrives as a checkpoint, and
 * phase-local instructions are rendered from allowlisted run invariants.
 *
 * Every type here is closed (sealed or enum) and immutable, so a projection set is a design-time
 * property of the workflow. There is deliberately no API that lets an executing agent, a phase
 * output, a resumed prompt, or a caller argument add a source or widen a projection.
 */

/** Names a projection's single source. Closed set: a new source kind requires a code change. */
sealed interface FeatureTaskRuntimeHandoffSourceRef {
  val wireValue: String

  /** The latest recorded output of one producing phase, delivered as a bounded receipt. */
  data class UpstreamPhaseOutput(val producingPhaseId: String) : FeatureTaskRuntimeHandoffSourceRef {
    init {
      require(producingPhaseId.isNotBlank()) {
        "FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput.producingPhaseId must be non-blank."
      }
    }

    override val wireValue: String get() = "$UPSTREAM_PHASE_OUTPUT_PREFIX$producingPhaseId"
  }

  /** One allowlisted run-invariant field. */
  data class RunInvariantField(val invariantField: FeatureTaskRuntimeRunInvariantPromptField) :
    FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = "$RUN_INVARIANT_FIELD_PREFIX${invariantField.wireValue}"
  }

  /** The ceremony scaling derived from the resolved feature size. */
  object DerivedCeremonyScaling : FeatureTaskRuntimeHandoffSourceRef {
    override val wireValue: String get() = DERIVED_CEREMONY_SCALING_WIRE
  }

  /** Hydrated content of one selected add-on, budgeted separately from phase receipts. */
  data class AddonContentRef(val slug: String) : FeatureTaskRuntimeHandoffSourceRef {
    init {
      require(slug.isNotBlank()) { "FeatureTaskRuntimeHandoffSourceRef.AddonContentRef.slug must be non-blank." }
    }

    override val wireValue: String get() = "$ADDON_CONTENT_PREFIX$slug"
  }

  companion object {
    const val UPSTREAM_PHASE_OUTPUT_PREFIX: String = "upstream_phase_output:"
    const val RUN_INVARIANT_FIELD_PREFIX: String = "run_invariant_field:"
    const val ADDON_CONTENT_PREFIX: String = "addon_content:"
    const val DERIVED_CEREMONY_SCALING_WIRE: String = "derived_ceremony_scaling"

    fun fromWire(value: String): FeatureTaskRuntimeHandoffSourceRef = when {
      value == DERIVED_CEREMONY_SCALING_WIRE -> DerivedCeremonyScaling
      value.startsWith(UPSTREAM_PHASE_OUTPUT_PREFIX) ->
        UpstreamPhaseOutput(value.removePrefix(UPSTREAM_PHASE_OUTPUT_PREFIX))
      value.startsWith(RUN_INVARIANT_FIELD_PREFIX) ->
        RunInvariantField(
          FeatureTaskRuntimeRunInvariantPromptField.fromWire(value.removePrefix(RUN_INVARIANT_FIELD_PREFIX)),
        )
      value.startsWith(ADDON_CONTENT_PREFIX) -> AddonContentRef(value.removePrefix(ADDON_CONTENT_PREFIX))
      else -> throw IllegalArgumentException("Unknown feature-task-runtime handoff source ref '$value'.")
    }
  }
}

/**
 * Distinguishes run identity from the other run-invariant categories so a per-phase allowlist can
 * select prompt-visible invariants without collapsing them into one undifferentiated block.
 */
enum class FeatureTaskRuntimeRunInvariantFieldCategory {
  IDENTITY,
  ACCEPTANCE_CONTRACT,
  POLICY,
  CEREMONY,
  REVIEW,
  ADD_ON,
  FINALIZATION,
}

enum class FeatureTaskRuntimeRunInvariantPromptField(
  val wireValue: String,
  val category: FeatureTaskRuntimeRunInvariantFieldCategory,
) {
  SPEC_REFERENCE("spec_reference", FeatureTaskRuntimeRunInvariantFieldCategory.IDENTITY),
  FEATURE_SIZE("feature_size", FeatureTaskRuntimeRunInvariantFieldCategory.IDENTITY),
  CEREMONY_SCALING("ceremony_scaling", FeatureTaskRuntimeRunInvariantFieldCategory.CEREMONY),
  ACCEPTANCE_CRITERIA("acceptance_criteria", FeatureTaskRuntimeRunInvariantFieldCategory.ACCEPTANCE_CONTRACT),
  MANDATES_AND_OVERRIDES("mandates_and_overrides", FeatureTaskRuntimeRunInvariantFieldCategory.POLICY),
  REVIEW_POLICY("review_policy", FeatureTaskRuntimeRunInvariantFieldCategory.REVIEW),
  AGENT_ADDONS("agent_addons", FeatureTaskRuntimeRunInvariantFieldCategory.ADD_ON),
  FINALIZATION_CONTEXT("finalization_context", FeatureTaskRuntimeRunInvariantFieldCategory.FINALIZATION),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeRunInvariantPromptField =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException("Unknown feature-task-runtime run-invariant prompt field '$value'.")
  }
}

enum class FeatureTaskRuntimeHandoffPromptVisibility(val wireValue: String) {
  PROMPT_VISIBLE("prompt_visible"),
  PRIVATE_EVIDENCE_ONLY("private_evidence_only"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeHandoffPromptVisibility =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException("Unknown feature-task-runtime handoff prompt visibility '$value'.")
  }
}

/**
 * Per-projection budget. Both dimensions are counted before prompt serialization; an overflow is a
 * rejection, never a truncation, so the consumer either receives the whole projection or none of it.
 */
data class FeatureTaskRuntimeHandoffProjectionBudget(
  val maxUtf8Bytes: Int,
  val maxCollectionItems: Int,
) {
  init {
    require(maxUtf8Bytes > 0) {
      "FeatureTaskRuntimeHandoffProjectionBudget.maxUtf8Bytes must be positive, was $maxUtf8Bytes."
    }
    require(maxCollectionItems > 0) {
      "FeatureTaskRuntimeHandoffProjectionBudget.maxCollectionItems must be positive, was $maxCollectionItems."
    }
  }

  companion object {
    /**
     * Sized against recorded runtime phase outputs: across 239 durable outputs no phase other than
     * `preplan` exceeded 20,844 UTF-8 bytes, so this leaves better than 3x headroom while a coarse
     * whole-receipt projection is still the delivered shape. A rejection here means a phase output
     * grew far beyond every observed size, not that an ordinary run outgrew its budget.
     */
    val PHASE_RECEIPT: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 65_536, maxCollectionItems = 64)

    /**
     * The preplanning digest is the one phase output that routinely runs an order of magnitude
     * larger than the rest; the same 239 recorded outputs put its maximum at 131,901 UTF-8 bytes.
     * Its single consumer (`plan`) therefore gets its own budget rather than forcing every edge up.
     */
    val PREPLAN_DIGEST_RECEIPT: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 196_608, maxCollectionItems = 64)

    /**
     * Sized against the manifest-declared feature-task add-on consumers: the largest shipped consumer
     * set (the kmp pack's seven `feature-task` add-ons) sums to 31,863 UTF-8 bytes, so 96 KiB leaves
     * better than 3x headroom while add-on content stays bounded independently of the phase-receipt
     * budget. A rejection here means an add-on payload grew far beyond every shipped consumer set, not
     * that an ordinary run outgrew its budget.
     */
    val ADDON_CONTENT: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(maxUtf8Bytes = 98_304, maxCollectionItems = 16)

    /**
     * The bounded planning projections deliver many typed lists rather than one whole-receipt text
     * field, so [PHASE_RECEIPT]'s item cap — sized when a projection was worth a single item — would
     * reject an ordinary feature's implementation receipt long before its byte budget was near.
     *
     * The cap is therefore derived from the projections' own per-list caps, which the schema repeats
     * as `maxItems`: the widest variant is the implementation receipt, at one `changed_paths` list,
     * six ordinary lists, and its two scalar fields. Model, schema, and budget agree by construction,
     * so a schema-valid projection can never overflow the budget it is delivered under, and an
     * overflow here means a producer bypassed the model's own validation.
     */
    val PLANNING_PROJECTION: FeatureTaskRuntimeHandoffProjectionBudget =
      FeatureTaskRuntimeHandoffProjectionBudget(
        maxUtf8Bytes = 196_608,
        maxCollectionItems = FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT +
          (IMPLEMENTATION_RECEIPT_ORDINARY_LIST_FIELDS * FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT) +
          IMPLEMENTATION_RECEIPT_SCALAR_FIELDS,
      )

    /** completed_task_ids, tests_added, tests_updated, tests_executed, deviations, unresolved_items. */
    private const val IMPLEMENTATION_RECEIPT_ORDINARY_LIST_FIELDS: Int = 6

    /** reconciliation_evidence and repository_checkpoint. */
    private const val IMPLEMENTATION_RECEIPT_SCALAR_FIELDS: Int = 2
  }
}

enum class FeatureTaskRuntimeRepositoryCheckpointPolicy(val wireValue: String) {
  NOT_REQUIRED("not_required"),
  MUST_MATCH("must_match"),
  REFRESH_FROM_REPOSITORY("refresh_from_repository"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeRepositoryCheckpointPolicy =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException("Unknown feature-task-runtime repository checkpoint policy '$value'.")
  }
}

/**
 * Deterministic identification of the repository state a projection was derived against. [baseRef]
 * and [headRef] carry the base/head pair when the caller knows it; [fingerprint] is the equivalent
 * digest every policy compares on.
 */
data class FeatureTaskRuntimeRepositoryCheckpoint(
  val fingerprint: String,
  val baseRef: String? = null,
  val headRef: String? = null,
  val workingTreeOwnedPaths: List<String> = emptyList(),
) {
  init {
    require(fingerprint.isNotBlank()) {
      "FeatureTaskRuntimeRepositoryCheckpoint.fingerprint must be non-blank; an unidentified checkpoint " +
        "cannot satisfy must_match or refresh_from_repository."
    }
    require(workingTreeOwnedPaths.none(String::isBlank)) {
      "FeatureTaskRuntimeRepositoryCheckpoint.workingTreeOwnedPaths must not contain blank entries."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime repository checkpoint at the handoff-envelope wire seam")
  fun toEnvelopeMap(): Map<String, Any?> = linkedMapOf<String, Any?>("fingerprint" to fingerprint).apply {
    baseRef?.let { put("base_ref", it) }
    headRef?.let { put("head_ref", it) }
    if (workingTreeOwnedPaths.isNotEmpty()) put("working_tree_owned_paths", workingTreeOwnedPaths)
  }
}

/**
 * Compact-reference kinds. [runtimeResolvable] marks the kinds a runtime-owned deterministic
 * operation can dereference; the others are plain identifiers the consumer reads as-is. No kind
 * introduces arbitrary model-driven retrieval.
 */
enum class FeatureTaskRuntimeCompactReferenceKind(val wireValue: String, val runtimeResolvable: Boolean) {
  PRIVATE_EVIDENCE_ARTIFACT("private_evidence_artifact", true),
  REPOSITORY_PATH("repository_path", true),
  REPOSITORY_CHECKPOINT("repository_checkpoint", false),
  ACCEPTANCE_CRITERION_REF("acceptance_criterion_ref", false),
  REPAIR_ITEM_ID("repair_item_id", false),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeCompactReferenceKind = entries.firstOrNull { it.wireValue == value }
      ?: throw IllegalArgumentException("Unknown feature-task-runtime compact reference kind '$value'.")
  }
}

/** Closed set of projection value shapes. Nothing here can carry a nested open map or raw blob. */
sealed interface FeatureTaskRuntimeHandoffProjectionValue {
  val utf8ByteSize: Int
  val itemCount: Int

  data class Text(val text: String) : FeatureTaskRuntimeHandoffProjectionValue {
    override val utf8ByteSize: Int get() = text.toByteArray(Charsets.UTF_8).size
    override val itemCount: Int get() = 1
  }

  data class TextList(val items: List<String>) : FeatureTaskRuntimeHandoffProjectionValue {
    override val utf8ByteSize: Int get() = items.sumOf { it.toByteArray(Charsets.UTF_8).size }
    override val itemCount: Int get() = items.size
  }

  data class CompactReference(
    val kind: FeatureTaskRuntimeCompactReferenceKind,
    val value: String,
  ) : FeatureTaskRuntimeHandoffProjectionValue {
    override val utf8ByteSize: Int get() = value.toByteArray(Charsets.UTF_8).size
    override val itemCount: Int get() = 1
  }
}

/** Field names a projection must never carry, because each names an unbounded raw-context channel. */
val FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES: Set<String> = setOf(
  "upstream_outputs_by_phase_id",
  "raw_payload",
  "payload",
  "raw_prompt",
  "prompt",
  "transcript",
  "tool_output",
  "log",
  "logs",
  "source_body",
  "diff_body",
  "telemetry",
)

private val PROJECTION_NAME_PATTERN = Regex("^[a-z][a-z0-9_]*$")

data class FeatureTaskRuntimeHandoffProjectionField(
  val name: String,
  val value: FeatureTaskRuntimeHandoffProjectionValue,
) {
  init {
    require(PROJECTION_NAME_PATTERN.matches(name)) {
      "FeatureTaskRuntimeHandoffProjectionField.name must match ${PROJECTION_NAME_PATTERN.pattern}, was '$name'."
    }
    require(name !in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES) {
      "FeatureTaskRuntimeHandoffProjectionField.name '$name' is a forbidden raw-context field."
    }
  }
}

/**
 * The AC-001 declaration: static, workflow-owned configuration naming exactly one projection for one
 * consumer phase. [declaredFieldNames] closes the projection's shape, so a field outside the list is
 * an undeclared field rather than a silently accepted extension.
 */
@Suppress("LongParameterList") // one flat declaration record; grouping would hide the governed fields
data class PhaseHandoffProjectionDeclaration(
  val consumerPhaseId: String,
  val sourceRef: FeatureTaskRuntimeHandoffSourceRef,
  val projectionName: String,
  val projectionContractId: String,
  val projectionContractVersion: String,
  val promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility,
  val budget: FeatureTaskRuntimeHandoffProjectionBudget,
  val declaredFieldNames: List<String>,
  val checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
    FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  val required: Boolean = true,
  /**
   * AC-010 gate: a lossless private-artifact reference may stand in for inline content only where the
   * consumer dereferences it through a runtime-owned deterministic operation.
   */
  val allowsPrivateArtifactReference: Boolean = false,
  /**
   * When set, the projection delivers a compact reference of this kind in place of inline content.
   * The reference is minted by the runtime from the source's durable identity, so dereferencing it
   * is a deterministic runtime operation — never a model-driven retrieval.
   */
  val inlineAlternative: FeatureTaskRuntimeCompactReferenceKind? = null,
) {
  init {
    require(consumerPhaseId.isNotBlank()) { "PhaseHandoffProjectionDeclaration.consumerPhaseId must be non-blank." }
    require(PROJECTION_NAME_PATTERN.matches(projectionName)) {
      "PhaseHandoffProjectionDeclaration.projectionName must match ${PROJECTION_NAME_PATTERN.pattern}, " +
        "was '$projectionName'."
    }
    require(projectionContractId.isNotBlank()) {
      "PhaseHandoffProjectionDeclaration.projectionContractId must be non-blank."
    }
    require(projectionContractVersion.isNotBlank()) {
      "PhaseHandoffProjectionDeclaration.projectionContractVersion must be non-blank."
    }
    require(declaredFieldNames.isNotEmpty()) {
      "PhaseHandoffProjectionDeclaration '$projectionName' must declare at least one field name; an open " +
        "projection shape cannot be validated."
    }
    require(declaredFieldNames.distinct().size == declaredFieldNames.size) {
      "PhaseHandoffProjectionDeclaration '$projectionName' declares duplicate field names."
    }
    require(declaredFieldNames.none { it in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES }) {
      "PhaseHandoffProjectionDeclaration '$projectionName' declares a forbidden raw-context field name."
    }
  }
}

/** One validated projection actually delivered to a consumer phase. */
data class FeatureTaskRuntimeHandoffProjection(
  val projectionName: String,
  val sourceRef: FeatureTaskRuntimeHandoffSourceRef,
  val projectionContractId: String,
  val projectionContractVersion: String,
  val promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility,
  val fields: List<FeatureTaskRuntimeHandoffProjectionField>,
) {
  val utf8ByteSize: Int get() = fields.sumOf { it.value.utf8ByteSize }
  val itemCount: Int get() = fields.sumOf { it.value.itemCount }

  @OpenBoundaryMap("Feature-task-runtime handoff projection at the durable envelope wire seam")
  fun toEnvelopeMap(): Map<String, Any?> = linkedMapOf(
    "projection_name" to projectionName,
    "source_ref" to sourceRef.wireValue,
    "projection_contract_id" to projectionContractId,
    "projection_contract_version" to projectionContractVersion,
    "prompt_visibility" to promptVisibility.wireValue,
    "fields" to fields.map { field ->
      linkedMapOf<String, Any?>("name" to field.name).apply {
        when (val value = field.value) {
          is FeatureTaskRuntimeHandoffProjectionValue.Text -> {
            put("kind", "text")
            put("text", value.text)
          }
          is FeatureTaskRuntimeHandoffProjectionValue.TextList -> {
            put("kind", "text_list")
            put("items", value.items)
          }
          is FeatureTaskRuntimeHandoffProjectionValue.CompactReference -> {
            put("kind", "compact_reference")
            put("reference_kind", value.kind.wireValue)
            put("reference_value", value.value)
          }
        }
      }
    },
  )
}

/**
 * The durable, prompt-visible handoff envelope: named typed projections and compact references only.
 * There is intentionally no generic upstream-payload map, raw payload, prompt, transcript, tool
 * output, log, source body, diff body, or telemetry field anywhere in this shape.
 */
data class FeatureTaskRuntimeHandoffEnvelope(
  val consumerPhaseId: String,
  val projections: List<FeatureTaskRuntimeHandoffProjection> = emptyList(),
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val contractVersion: String = FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION,
) {
  init {
    require(consumerPhaseId.isNotBlank()) { "FeatureTaskRuntimeHandoffEnvelope.consumerPhaseId must be non-blank." }
    require(contractVersion.isNotBlank()) { "FeatureTaskRuntimeHandoffEnvelope.contractVersion must be non-blank." }
    val names = projections.map { it.projectionName }
    require(names.distinct().size == names.size) {
      "FeatureTaskRuntimeHandoffEnvelope for '$consumerPhaseId' contains duplicate projection names."
    }
  }

  /** Only the prompt-visible projections reach prompt composition; private ones never render. */
  val promptVisibleProjections: List<FeatureTaskRuntimeHandoffProjection>
    get() = projections.filter { it.promptVisibility == FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE }

  @OpenBoundaryMap("Feature-task-runtime handoff envelope at the durable workflow-artifact seam")
  fun toEnvelopeMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to contractVersion,
    "consumer_phase_id" to consumerPhaseId,
    "projections" to projections.map { it.toEnvelopeMap() },
  ).apply {
    repositoryCheckpoint?.let { put("repository_checkpoint", it.toEnvelopeMap()) }
  }

  companion object {
    @OpenBoundaryMap("Feature-task-runtime handoff envelope decode from the durable workflow-artifact map")
    fun fromEnvelopeMap(raw: Map<String, Any?>): FeatureTaskRuntimeHandoffEnvelope = FeatureTaskRuntimeHandoffEnvelope(
      consumerPhaseId = raw.requireString("consumer_phase_id"),
      projections = (raw["projections"] as? List<*>).orEmpty().map { projectionFromWire(it) },
      repositoryCheckpoint = (raw["repository_checkpoint"] as? Map<*, *>)?.let { checkpoint ->
        FeatureTaskRuntimeRepositoryCheckpoint(
          fingerprint = checkpoint.requireString("fingerprint"),
          baseRef = checkpoint["base_ref"] as? String,
          headRef = checkpoint["head_ref"] as? String,
          workingTreeOwnedPaths = (checkpoint["working_tree_owned_paths"] as? List<*>).orEmpty()
            .map { it.requireDecodedString("working_tree_owned_paths") },
        )
      },
      contractVersion = raw.requireString("contract_version"),
    )

    private fun projectionFromWire(raw: Any?): FeatureTaskRuntimeHandoffProjection {
      val projection = raw as? Map<*, *> ?: decodeError("projections entries must be objects.")
      return FeatureTaskRuntimeHandoffProjection(
        projectionName = projection.requireString("projection_name"),
        sourceRef = FeatureTaskRuntimeHandoffSourceRef.fromWire(projection.requireString("source_ref")),
        projectionContractId = projection.requireString("projection_contract_id"),
        projectionContractVersion = projection.requireString("projection_contract_version"),
        promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility
          .fromWire(projection.requireString("prompt_visibility")),
        fields = (projection["fields"] as? List<*>).orEmpty().map(::fieldFromWire),
      )
    }

    private fun fieldFromWire(raw: Any?): FeatureTaskRuntimeHandoffProjectionField {
      val field = raw as? Map<*, *> ?: decodeError("projection fields entries must be objects.")
      val name = field.requireString("name")
      return FeatureTaskRuntimeHandoffProjectionField(
        name = name,
        value = when (val kind = field.requireString("kind")) {
          "text" -> FeatureTaskRuntimeHandoffProjectionValue.Text(field.requireString("text"))
          "text_list" -> FeatureTaskRuntimeHandoffProjectionValue.TextList(
            (field["items"] as? List<*>).orEmpty().map { it.requireDecodedString("items") },
          )
          "compact_reference" -> FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
            kind = FeatureTaskRuntimeCompactReferenceKind.fromWire(field.requireString("reference_kind")),
            value = field.requireString("reference_value"),
          )
          else -> decodeError("projection field '$name' has unknown value kind '$kind'.")
        },
      )
    }

    private fun decodeError(detail: String): Nothing =
      throw IllegalArgumentException("Feature-task-runtime handoff envelope is malformed: $detail")

    private fun Map<*, *>.requireString(key: String): String = (this[key] as? String)?.takeIf(String::isNotBlank)
      ?: decodeError("field '$key' must decode to a non-blank string.")

    private fun Any?.requireDecodedString(key: String): String =
      this as? String ?: decodeError("field '$key' must contain strings.")
  }
}

/** Everything the validator may read while projecting; no open map, no agent-supplied channel. */
data class FeatureTaskRuntimeHandoffProjectionInputs(
  val consumerPhaseId: String,
  val declarations: List<PhaseHandoffProjectionDeclaration>,
  val resolvedUpstream: FeatureTaskRuntimeResolvedUpstreamOutputs,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
  /** Freshly resolved repository checkpoint, when the application layer resolved one. */
  val resolvedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  /** Checkpoint recorded in durable state, compared against the resolved one under `must_match`. */
  val expectedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val addonContentBySlug: Map<String, String> = emptyMap(),
  val workflowId: String? = null,
  /**
   * Canonical planning-projections schema gate, called before a bounded projection is parsed. The
   * default leaves the schema unenforced and exists only for suites asserting the typed Kotlin rules
   * in isolation; production wiring passes the infra-fs-backed adapter.
   */
  val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator =
    NoopFeatureTaskRuntimePlanningProjectionValidator,
)
