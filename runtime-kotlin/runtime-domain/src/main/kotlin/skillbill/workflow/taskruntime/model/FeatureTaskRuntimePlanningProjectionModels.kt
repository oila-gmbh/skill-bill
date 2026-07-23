@file:Suppress("TooManyFunctions", "MaxLineLength")

package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePlanningProjectionSchemaError

/**
 * Typed models for the four concrete bounded planning projections (preplanning digest, executable plan,
 * plan commitment, implementation receipt). Each is closed and immutable, parses the producing phase's
 * schema-validated `produced_outputs`, and renders the exact field set a consumer projection delivers —
 * never the producing phase's complete envelope, narration, presentation summary, progress diagnostics,
 * or raw source.
 *
 * Field-name constants ([DECLARED_FIELD_NAMES]) are the single source a handoff declaration references, so
 * the delivered projection shape and the declared shape cannot drift.
 */

enum class FeatureTaskRuntimeProjectionKind(val wireValue: String) {
  PREPLANNING_DIGEST("preplanning_digest"),
  EXECUTABLE_PLAN("executable_plan"),
  PLAN_COMMITMENT("plan_commitment"),
  IMPLEMENTATION_RECEIPT("implementation_receipt"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeProjectionKind = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = "<projection_kind>",
        reason = "unknown projection_kind '$value'; expected one of ${entries.joinToString { it.wireValue }}.",
      )
  }
}

object FeatureTaskRuntimePlanningProjectionContract {
  const val PREPLANNING_DIGEST_ID: String = "feature_task_runtime.preplanning_digest"
  const val EXECUTABLE_PLAN_ID: String = "feature_task_runtime.executable_plan"
  const val PLAN_COMMITMENT_ID: String = "feature_task_runtime.plan_commitment"
  const val IMPLEMENTATION_RECEIPT_ID: String = "feature_task_runtime.implementation_receipt"
  val VERSION: String = FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION
}

// Repository-relative path: no leading slash, no backslash, no `..` segment. Mirrors the schema `repoPath`
// $def and review-context-schema.yaml's `path` $def so normalization is identical everywhere.
private val REPO_PATH_PATTERN = Regex("^(?!/)(?!.*\\\\)(?!.*(?:^|/)\\.\\.(?:/|$)).+$")
private val TASK_ID_PATTERN = Regex("^[a-z][a-z0-9-]*$")
private const val REPO_PATH_MAX_LENGTH: Int = 1024
private const val TEXT_MAX_LENGTH: Int = 4096
private const val CHANGED_PATH_MAX_COUNT: Int = 512

/**
 * The bounded digest `plan` receives from `preplan` (AC-003). Excludes the complete preplan envelope,
 * its summary, derived notes, and any progress diagnostics.
 */
data class FeatureTaskRuntimePrePlanningDigest(
  val affectedBoundaries: List<String>,
  val patternsAndDecisions: List<String> = emptyList(),
  val risks: List<String>,
  val rollout: FeatureTaskRuntimeRolloutDecision,
  val validationStrategy: List<String>,
  val unresolvedQuestions: List<String> = emptyList(),
  val evidenceRefs: List<String> = emptyList(),
) {
  init {
    requireNonBlankStrings(affectedBoundaries, "affected_boundaries")
    requireNonBlankStrings(risks, "risks")
    requireNonBlankStrings(validationStrategy, "validation_strategy")
    requireNonBlankStrings(patternsAndDecisions, "patterns_and_decisions")
    requireNonBlankStrings(unresolvedQuestions, "unresolved_questions")
    requireNonBlankStrings(evidenceRefs, "evidence_refs")
  }

  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> = listOf(
    field(FIELD_AFFECTED_BOUNDARIES, FeatureTaskRuntimeHandoffProjectionValue.TextList(affectedBoundaries)),
    field(FIELD_PATTERNS_AND_DECISIONS, FeatureTaskRuntimeHandoffProjectionValue.TextList(patternsAndDecisions)),
    field(FIELD_RISKS, FeatureTaskRuntimeHandoffProjectionValue.TextList(risks)),
    field(FIELD_ROLLOUT, FeatureTaskRuntimeHandoffProjectionValue.Text(rollout.toBriefingLine())),
    field(FIELD_VALIDATION_STRATEGY, FeatureTaskRuntimeHandoffProjectionValue.TextList(validationStrategy)),
    field(FIELD_UNRESOLVED_QUESTIONS, FeatureTaskRuntimeHandoffProjectionValue.TextList(unresolvedQuestions)),
    field(FIELD_EVIDENCE_REFS, FeatureTaskRuntimeHandoffProjectionValue.TextList(evidenceRefs)),
  )

  companion object {
    val DECLARED_FIELD_NAMES: List<String> = listOf(
      FIELD_AFFECTED_BOUNDARIES,
      FIELD_PATTERNS_AND_DECISIONS,
      FIELD_RISKS,
      FIELD_ROLLOUT,
      FIELD_VALIDATION_STRATEGY,
      FIELD_UNRESOLVED_QUESTIONS,
      FIELD_EVIDENCE_REFS,
    )

    const val FIELD_AFFECTED_BOUNDARIES: String = "affected_boundaries"
    const val FIELD_PATTERNS_AND_DECISIONS: String = "patterns_and_decisions"
    const val FIELD_RISKS: String = "risks"
    const val FIELD_ROLLOUT: String = "rollout"
    const val FIELD_VALIDATION_STRATEGY: String = "validation_strategy"
    const val FIELD_UNRESOLVED_QUESTIONS: String = "unresolved_questions"
    const val FIELD_EVIDENCE_REFS: String = "evidence_refs"
  }
}

data class FeatureTaskRuntimeRolloutDecision(
  val flagRequired: Boolean,
  val flagPattern: FeatureTaskRuntimeFlagPattern = FeatureTaskRuntimeFlagPattern.NONE,
  val notes: String,
) {
  init {
    require(notes.isNotBlank()) { "FeatureTaskRuntimeRolloutDecision.notes must be non-blank." }
    require(
      notes.length <= TEXT_MAX_LENGTH,
    ) { "FeatureTaskRuntimeRolloutDecision.notes exceeds $TEXT_MAX_LENGTH chars." }
  }

  fun toBriefingLine(): String = "flag_required=$flagRequired; flag_pattern=${flagPattern.wireValue}; notes=$notes"
}

enum class FeatureTaskRuntimeFlagPattern(val wireValue: String) {
  NONE("none"),
  SIMPLE_CONDITIONAL("simple_conditional"),
  DI_SWITCH("di_switch"),
  LEGACY("legacy"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeFlagPattern = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = "<rollout.flag_pattern>",
        reason = "unknown flag_pattern '$value'.",
      )
  }
}

enum class FeatureTaskRuntimePlanMode(val wireValue: String) {
  DIRECT("direct"),
  DECOMPOSE("decompose"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimePlanMode = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = "<mode>",
        reason = "unknown executable_plan mode '$value'; expected direct or decompose.",
      )
  }
}

/**
 * The executable plan `implement` receives from `plan` (AC-005). Carries stable ordered task ids,
 * dependencies (validated acyclic), descriptions, criterion refs, target paths/symbols, test
 * obligations, constraints, and validation strategy. Excludes planning narration, presentation summary,
 * and generic producer summary. Decomposition data is forbidden under DIRECT (AC-015): a DECOMPOSE plan
 * stays private to the preparation/goal boundary and never reaches implementation.
 */
data class FeatureTaskRuntimeExecutablePlan(
  val mode: FeatureTaskRuntimePlanMode,
  val tasks: List<FeatureTaskRuntimePlanTask>,
  val validationStrategy: List<String>,
  val decompositionSubtaskCount: Int? = null,
  val decompositionManifestRef: String? = null,
) {
  init {
    require(tasks.isNotEmpty()) { "FeatureTaskRuntimeExecutablePlan.tasks must contain at least one task." }
    val ids = tasks.map { it.taskId }
    require(ids.distinct().size == ids.size) {
      "FeatureTaskRuntimeExecutablePlan task ids must be unique, duplicated ${ids.duplicates()}."
    }
    requireAcyclicTasks(tasks)
    requireNonBlankStrings(validationStrategy, "validation_strategy")
    require(mode == FeatureTaskRuntimePlanMode.DIRECT || decompositionSubtaskCount != null) {
      "FeatureTaskRuntimeExecutablePlan: decomposition_subtask_count is required under DECOMPOSE mode."
    }
  }

  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> = listOf(
    field(FIELD_MODE, FeatureTaskRuntimeHandoffProjectionValue.Text(mode.wireValue)),
    field(FIELD_TASKS, FeatureTaskRuntimeHandoffProjectionValue.TextList(tasks.map { it.toBriefingLine() })),
    field(FIELD_VALIDATION_STRATEGY, FeatureTaskRuntimeHandoffProjectionValue.TextList(validationStrategy)),
  )

  /** The bounded commitment forwarded to audit: task/criterion/test obligations only (AC-011). */
  fun toPlanCommitment(): FeatureTaskRuntimePlanCommitment = FeatureTaskRuntimePlanCommitment(
    taskCommitments = tasks.map { task ->
      FeatureTaskRuntimeTaskCommitment(
        taskId = task.taskId,
        criterionRefs = task.criterionRefs,
        testObligations = task.testObligations,
        constraints = task.constraints,
      )
    },
  )

  companion object {
    val DECLARED_FIELD_NAMES: List<String> = listOf(FIELD_MODE, FIELD_TASKS, FIELD_VALIDATION_STRATEGY)
    const val FIELD_MODE: String = "mode"
    const val FIELD_TASKS: String = "tasks"
    const val FIELD_VALIDATION_STRATEGY: String = "validation_strategy"
  }
}

data class FeatureTaskRuntimePlanTask(
  val taskId: String,
  val dependsOn: List<String> = emptyList(),
  val description: String,
  val criterionRefs: List<String>,
  val targetPathsOrSymbols: List<String> = emptyList(),
  val testObligations: List<String>,
  val constraints: List<String> = emptyList(),
) {
  init {
    require(TASK_ID_PATTERN.matches(taskId)) {
      "FeatureTaskRuntimePlanTask.taskId must match ${TASK_ID_PATTERN.pattern}, was '$taskId'."
    }
    require(description.isNotBlank()) { "FeatureTaskRuntimePlanTask.description must be non-blank." }
    require(description.length <= TEXT_MAX_LENGTH) {
      "FeatureTaskRuntimePlanTask.description exceeds $TEXT_MAX_LENGTH chars."
    }
    requireNonBlankStrings(criterionRefs, "criterion_refs")
    criterionRefs.forEach { requireCriterionRef(it) }
    requireNonBlankStrings(testObligations, "test_obligations")
    requireNonBlankStrings(constraints, "constraints")
    requireNonBlankStrings(targetPathsOrSymbols, "target_paths_or_symbols")
    dependsOn.forEach { dependency ->
      require(TASK_ID_PATTERN.matches(dependency)) {
        "FeatureTaskRuntimePlanTask.dependsOn entry '$dependency' must match ${TASK_ID_PATTERN.pattern}."
      }
    }
  }

  fun toBriefingLine(): String = buildString {
    append(taskId)
    if (dependsOn.isNotEmpty()) append(" [depends: ${dependsOn.joinToString(",")}]")
    append(" ${criterionRefs.joinToString(",")}: $description")
  }
}

/**
 * Bounded subset of the executable plan delivered to `audit` (AC-011): task/criterion/test obligations
 * only. No description, target paths, narration, presentation summary, or validation strategy.
 */
data class FeatureTaskRuntimePlanCommitment(
  val taskCommitments: List<FeatureTaskRuntimeTaskCommitment>,
) {
  init {
    require(taskCommitments.isNotEmpty()) { "FeatureTaskRuntimePlanCommitment.taskCommitments must be non-empty." }
    val ids = taskCommitments.map { it.taskId }
    require(ids.distinct().size == ids.size) {
      "FeatureTaskRuntimePlanCommitment task ids must be unique, duplicated ${ids.duplicates()}."
    }
  }

  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> = listOf(
    field(
      FIELD_TASK_COMMITMENTS,
      FeatureTaskRuntimeHandoffProjectionValue.TextList(taskCommitments.map { it.toBriefingLine() }),
    ),
  )

  companion object {
    val DECLARED_FIELD_NAMES: List<String> = listOf(FIELD_TASK_COMMITMENTS)
    const val FIELD_TASK_COMMITMENTS: String = "task_commitments"
  }
}

data class FeatureTaskRuntimeTaskCommitment(
  val taskId: String,
  val criterionRefs: List<String>,
  val testObligations: List<String>,
  val constraints: List<String> = emptyList(),
) {
  init {
    require(TASK_ID_PATTERN.matches(taskId)) {
      "FeatureTaskRuntimeTaskCommitment.taskId must match ${TASK_ID_PATTERN.pattern}, was '$taskId'."
    }
    requireNonBlankStrings(criterionRefs, "criterion_refs")
    criterionRefs.forEach { requireCriterionRef(it) }
    requireNonBlankStrings(testObligations, "test_obligations")
    requireNonBlankStrings(constraints, "constraints")
  }

  fun toBriefingLine(): String = "$taskId ${criterionRefs.joinToString(",")}: ${testObligations.size} obligation(s)"
}

/**
 * The producer claim `audit` receives from `implement` (AC-008/009). A receipt is a CLAIM, not proof:
 * audit regenerates or reads the exact repository diff/state for the checkpoint and compares (AC-010).
 * Changed paths are normalized, unique, and count-bounded; tests_executed (name+outcome) are distinct
 * from the tests_added/tests_updated identifier lists.
 */
data class FeatureTaskRuntimeImplementationReceipt(
  val completedTaskIds: List<String>,
  val changedPaths: List<String>,
  val testsAdded: List<String> = emptyList(),
  val testsUpdated: List<String> = emptyList(),
  val testsExecuted: List<FeatureTaskRuntimeTestExecution>,
  val deviations: List<FeatureTaskRuntimeDeviation> = emptyList(),
  val unresolvedItems: List<String> = emptyList(),
  val reconciliationEvidence: FeatureTaskRuntimeReconciliationEvidence,
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint,
) {
  init {
    completedTaskIds.forEach {
      require(TASK_ID_PATTERN.matches(it)) { "completed_task_ids entry '$it' is not a valid task id." }
    }
    requireChangedPaths(changedPaths)
    requireNonBlankStrings(testsAdded, "tests_added", allowRepoPath = true)
    requireNonBlankStrings(testsUpdated, "tests_updated", allowRepoPath = true)
    requireNonBlankStrings(unresolvedItems, "unresolved_items")
    val executedNames = testsExecuted.map { it.name }
    require(executedNames.distinct().size == executedNames.size) {
      "tests_executed names must be unique, duplicated ${executedNames.duplicates()}."
    }
  }

  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> = listOf(
    field(FIELD_COMPLETED_TASK_IDS, FeatureTaskRuntimeHandoffProjectionValue.TextList(completedTaskIds)),
    field(FIELD_CHANGED_PATHS, FeatureTaskRuntimeHandoffProjectionValue.TextList(changedPaths)),
    field(FIELD_TESTS_ADDED, FeatureTaskRuntimeHandoffProjectionValue.TextList(testsAdded)),
    field(FIELD_TESTS_UPDATED, FeatureTaskRuntimeHandoffProjectionValue.TextList(testsUpdated)),
    field(
      FIELD_TESTS_EXECUTED,
      FeatureTaskRuntimeHandoffProjectionValue.TextList(testsExecuted.map { "${it.name}=${it.outcome.wireValue}" }),
    ),
    field(
      FIELD_DEVIATIONS,
      FeatureTaskRuntimeHandoffProjectionValue.TextList(deviations.map { "${it.ref}: ${it.note}" }),
    ),
    field(FIELD_UNRESOLVED_ITEMS, FeatureTaskRuntimeHandoffProjectionValue.TextList(unresolvedItems)),
    field(
      FIELD_RECONCILIATION_EVIDENCE,
      FeatureTaskRuntimeHandoffProjectionValue.Text(reconciliationEvidence.evidence),
    ),
    field(
      FIELD_REPOSITORY_CHECKPOINT,
      FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
        kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
        value = repositoryCheckpoint.fingerprint,
      ),
    ),
  )

  companion object {
    val DECLARED_FIELD_NAMES: List<String> = listOf(
      FIELD_COMPLETED_TASK_IDS,
      FIELD_CHANGED_PATHS,
      FIELD_TESTS_ADDED,
      FIELD_TESTS_UPDATED,
      FIELD_TESTS_EXECUTED,
      FIELD_DEVIATIONS,
      FIELD_UNRESOLVED_ITEMS,
      FIELD_RECONCILIATION_EVIDENCE,
      FIELD_REPOSITORY_CHECKPOINT,
    )

    const val FIELD_COMPLETED_TASK_IDS: String = "completed_task_ids"
    const val FIELD_CHANGED_PATHS: String = "changed_paths"
    const val FIELD_TESTS_ADDED: String = "tests_added"
    const val FIELD_TESTS_UPDATED: String = "tests_updated"
    const val FIELD_TESTS_EXECUTED: String = "tests_executed"
    const val FIELD_DEVIATIONS: String = "deviations"
    const val FIELD_UNRESOLVED_ITEMS: String = "unresolved_items"
    const val FIELD_RECONCILIATION_EVIDENCE: String = "reconciliation_evidence"
    const val FIELD_REPOSITORY_CHECKPOINT: String = "repository_checkpoint"
  }
}

data class FeatureTaskRuntimeTestExecution(
  val name: String,
  val outcome: FeatureTaskRuntimeTestOutcome,
) {
  init {
    requireRepoPath(name, "tests_executed.name")
  }
}

enum class FeatureTaskRuntimeTestOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  SKIPPED("skipped"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeTestOutcome = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
        sourceLabel = "<tests_executed.outcome>",
        reason = "unknown test outcome '$value'; expected passed, failed, or skipped.",
      )
  }
}

data class FeatureTaskRuntimeDeviation(
  val ref: String,
  val note: String,
) {
  init {
    require(ref.isNotBlank()) { "FeatureTaskRuntimeDeviation.ref must be non-blank." }
    require(ref.length <= TEXT_MAX_LENGTH) { "FeatureTaskRuntimeDeviation.ref exceeds $TEXT_MAX_LENGTH chars." }
    require(note.isNotBlank()) { "FeatureTaskRuntimeDeviation.note must be non-blank." }
    require(note.length <= TEXT_MAX_LENGTH) { "FeatureTaskRuntimeDeviation.note exceeds $TEXT_MAX_LENGTH chars." }
  }
}

data class FeatureTaskRuntimeReconciliationEvidence(
  val reconciled: Boolean,
  val evidence: String,
) {
  init {
    require(reconciled) {
      "FeatureTaskRuntimeReconciliationEvidence.reconciled must be true; an un-reconciled receipt cannot be delivered."
    }
    require(evidence.isNotBlank()) { "FeatureTaskRuntimeReconciliationEvidence.evidence must be non-blank." }
    require(evidence.length <= TEXT_MAX_LENGTH) {
      "FeatureTaskRuntimeReconciliationEvidence.evidence exceeds $TEXT_MAX_LENGTH chars."
    }
  }
}

private fun field(
  name: String,
  value: FeatureTaskRuntimeHandoffProjectionValue,
): FeatureTaskRuntimeHandoffProjectionField = FeatureTaskRuntimeHandoffProjectionField(name = name, value = value)

private fun requireNonBlankStrings(values: List<String>, field: String, allowRepoPath: Boolean = false) {
  values.forEach { value ->
    require(value.isNotBlank()) { "$field entries must be non-blank." }
    require(value.length <= REPO_PATH_MAX_LENGTH) { "$field entry exceeds $REPO_PATH_MAX_LENGTH chars." }
    if (allowRepoPath) requireRepoPath(value, "$field entry")
  }
}

private fun requireRepoPath(value: String, field: String) {
  require(REPO_PATH_PATTERN.matches(value)) {
    "$field '$value' must be a repository-relative path: no leading slash, no backslash, no `..` segment."
  }
}

private fun requireCriterionRef(value: String) {
  require(value.matches(Regex("^AC-[0-9]{3}$"))) {
    "criterion_ref '$value' must match AC-###."
  }
}

private fun requireChangedPaths(paths: List<String>) {
  require(paths.size <= CHANGED_PATH_MAX_COUNT) {
    "changed_paths allows at most $CHANGED_PATH_MAX_COUNT entries, had ${paths.size}."
  }
  val duplicates = paths.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
  require(duplicates.isEmpty()) { "changed_paths must be unique, duplicated $duplicates." }
  paths.forEach { requireRepoPath(it, "changed_paths entry") }
}

private fun requireAcyclicTasks(tasks: List<FeatureTaskRuntimePlanTask>) {
  val byId = tasks.associateBy { it.taskId }
  val visiting = mutableSetOf<String>()
  val visited = mutableSetOf<String>()
  fun visit(id: String) {
    if (id in visited) return
    val task = byId[id] ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = "<tasks.depends_on>",
      reason = "task '$id' is referenced as a dependency but is not declared.",
    )
    require(visiting.add(id)) { "Plan task dependencies must be acyclic (cycle at '$id')." }
    task.dependsOn.forEach { dependency ->
      if (dependency !in byId) {
        throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
          sourceLabel = "<tasks.depends_on>",
          reason = "task '$id' depends on undeclared task '$dependency'.",
        )
      }
      visit(dependency)
    }
    visiting.remove(id)
    visited.add(id)
  }
  byId.keys.forEach(::visit)
}

private fun List<String>.duplicates(): List<String> =
  groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

/**
 * Parses a producing phase's `produced_outputs` into the typed planning projection matching its
 * `projection_kind`. The whole producing envelope may be passed; only `produced_outputs` is read.
 * Throws [InvalidFeatureTaskRuntimePlanningProjectionSchemaError] on any malformed shape so a legacy
 * free-form payload loud-fails rather than being reinterpreted as a bounded projection.
 */
@OpenBoundaryMap("Feature-task-runtime planning projection parse from the schema-validated phase-output wire map")
fun featureTaskRuntimePlanningProjectionFromEnvelope(envelope: Map<String, Any?>, producingPhaseId: String): Any {
  val produced = envelope.stringAnyMap("produced_outputs")
    ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = "$producingPhaseId#produced_outputs",
      reason = "produced_outputs is missing or not an object.",
    )
  val kind = produced["projection_kind"]?.toString()
    ?: throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
      sourceLabel = "$producingPhaseId#produced_outputs",
      reason = "produced_outputs.projection_kind is missing; a bounded projection requires it.",
    )
  return when (FeatureTaskRuntimeProjectionKind.fromWire(kind)) {
    FeatureTaskRuntimeProjectionKind.PREPLANNING_DIGEST -> FeatureTaskRuntimePrePlanningDigest.fromMap(produced)
    FeatureTaskRuntimeProjectionKind.EXECUTABLE_PLAN -> FeatureTaskRuntimeExecutablePlan.fromMap(produced)
    FeatureTaskRuntimeProjectionKind.PLAN_COMMITMENT -> FeatureTaskRuntimePlanCommitment.fromMap(produced)
    FeatureTaskRuntimeProjectionKind.IMPLEMENTATION_RECEIPT -> FeatureTaskRuntimeImplementationReceipt.fromMap(produced)
  }
}

@Suppress("ThrowsCount") // one malformed-field rejection per required field; collapsing would blur the diagnosis
private fun FeatureTaskRuntimePrePlanningDigest.Companion.fromMap(
  map: Map<String, Any?>,
): FeatureTaskRuntimePrePlanningDigest {
  val rolloutMap = map.stringAnyMap("rollout")
    ?: throw malformed("rollout", "must be an object")
  return FeatureTaskRuntimePrePlanningDigest(
    affectedBoundaries = map.requireStringList("affected_boundaries"),
    patternsAndDecisions = map.optionalStringList("patterns_and_decisions"),
    risks = map.requireStringList("risks"),
    rollout = FeatureTaskRuntimeRolloutDecision(
      flagRequired = (rolloutMap["flag_required"] as? Boolean)
        ?: throw malformed("rollout.flag_required", "must be a boolean"),
      flagPattern = rolloutMap["flag_pattern"]?.toString()?.let(FeatureTaskRuntimeFlagPattern::fromWire)
        ?: FeatureTaskRuntimeFlagPattern.NONE,
      notes = rolloutMap.firstString("notes"),
    ),
    validationStrategy = map.requireStringList("validation_strategy"),
    unresolvedQuestions = map.optionalStringList("unresolved_questions"),
    evidenceRefs = map.optionalStringList("evidence_refs"),
  )
}

@Suppress("ThrowsCount") // one malformed-field rejection per required field; collapsing would blur the diagnosis
private fun FeatureTaskRuntimeExecutablePlan.Companion.fromMap(
  map: Map<String, Any?>,
): FeatureTaskRuntimeExecutablePlan {
  val mode = FeatureTaskRuntimePlanMode.fromWire(map["mode"]?.toString().orEmpty())
  val rawTasks = map["tasks"] as? List<*>
    ?: throw malformed("tasks", "must be a list")
  val tasks = rawTasks.mapIndexed { index, raw ->
    val taskMap = (raw as? Map<*, *>)?.entries?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?: throw malformed("tasks[$index]", "must be an object")
    FeatureTaskRuntimePlanTask(
      taskId = taskMap.requireString("task_id", "tasks[$index].task_id"),
      dependsOn = taskMap.optionalStringList("depends_on"),
      description = taskMap.requireString("description", "tasks[$index].description"),
      criterionRefs = taskMap.optionalStringList("criterion_refs").ifEmpty {
        throw malformed("tasks[$index].criterion_refs", "must contain at least one AC-### ref")
      },
      targetPathsOrSymbols = taskMap.optionalStringList("target_paths_or_symbols"),
      testObligations = taskMap.optionalStringList("test_obligations").ifEmpty {
        throw malformed("tasks[$index].test_obligations", "must contain at least one entry")
      },
      constraints = taskMap.optionalStringList("constraints"),
    )
  }
  return FeatureTaskRuntimeExecutablePlan(
    mode = mode,
    tasks = tasks,
    validationStrategy = map.requireStringList("validation_strategy"),
    decompositionSubtaskCount = (map["decomposition_subtask_count"] as? Number)?.toInt(),
    decompositionManifestRef = map["decomposition_manifest_ref"]?.toString()?.takeIf(String::isNotBlank),
  )
}

@Suppress("ThrowsCount") // one malformed-field rejection per required field; collapsing would blur the diagnosis
private fun FeatureTaskRuntimePlanCommitment.Companion.fromMap(
  map: Map<String, Any?>,
): FeatureTaskRuntimePlanCommitment {
  val rawCommitments = map["task_commitments"] as? List<*>
    ?: throw malformed("task_commitments", "must be a list")
  val commitments = rawCommitments.mapIndexed { index, raw ->
    val commitmentMap = (raw as? Map<*, *>)?.entries?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?: throw malformed("task_commitments[$index]", "must be an object")
    FeatureTaskRuntimeTaskCommitment(
      taskId = commitmentMap.requireString("task_id", "task_commitments[$index].task_id"),
      criterionRefs = commitmentMap.optionalStringList("criterion_refs").ifEmpty {
        throw malformed("task_commitments[$index].criterion_refs", "must contain at least one AC-### ref")
      },
      testObligations = commitmentMap.optionalStringList("test_obligations").ifEmpty {
        throw malformed("task_commitments[$index].test_obligations", "must contain at least one entry")
      },
      constraints = commitmentMap.optionalStringList("constraints"),
    )
  }
  return FeatureTaskRuntimePlanCommitment(taskCommitments = commitments)
}

@Suppress("ThrowsCount") // one malformed-field rejection per required field; collapsing would blur the diagnosis
private fun FeatureTaskRuntimeImplementationReceipt.Companion.fromMap(
  map: Map<String, Any?>,
): FeatureTaskRuntimeImplementationReceipt {
  val checkpointMap = map.stringAnyMap("repository_checkpoint")
    ?: throw malformed("repository_checkpoint", "must be an object")
  val reconciliationMap = map.stringAnyMap("reconciliation_evidence")
    ?: throw malformed("reconciliation_evidence", "must be an object")
  val rawExecuted = map["tests_executed"] as? List<*>
    ?: throw malformed("tests_executed", "must be a list")
  val executed = rawExecuted.mapIndexed { index, raw ->
    val execMap = (raw as? Map<*, *>)?.entries?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?: throw malformed("tests_executed[$index]", "must be an object")
    FeatureTaskRuntimeTestExecution(
      name = execMap.requireString("name", "tests_executed[$index].name"),
      outcome = FeatureTaskRuntimeTestOutcome.fromWire(
        execMap["outcome"]?.toString().orEmpty(),
      ),
    )
  }
  val rawDeviations = (map["deviations"] as? List<*>).orEmpty()
  val deviations = rawDeviations.mapIndexed { index, raw ->
    val devMap = (raw as? Map<*, *>)?.entries?.filter { it.key is String }
      ?.associate { it.key as String to it.value }
      ?: throw malformed("deviations[$index]", "must be an object")
    FeatureTaskRuntimeDeviation(
      ref = devMap.requireString("ref", "deviations[$index].ref"),
      note = devMap.requireString("note", "deviations[$index].note"),
    )
  }
  return FeatureTaskRuntimeImplementationReceipt(
    completedTaskIds = map.optionalStringList("completed_task_ids"),
    changedPaths = map.optionalStringList("changed_paths").ifEmpty {
      throw malformed("changed_paths", "must contain at least one repository-relative path")
    },
    testsAdded = map.optionalStringList("tests_added"),
    testsUpdated = map.optionalStringList("tests_updated"),
    testsExecuted = executed,
    deviations = deviations,
    unresolvedItems = map.optionalStringList("unresolved_items"),
    reconciliationEvidence = FeatureTaskRuntimeReconciliationEvidence(
      reconciled = (reconciliationMap["reconciled"] as? Boolean)
        ?: throw malformed("reconciliation_evidence.reconciled", "must be a boolean"),
      evidence = reconciliationMap.firstString("evidence"),
    ),
    repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(
      fingerprint = checkpointMap.requireString("fingerprint", "repository_checkpoint.fingerprint"),
      baseRef = checkpointMap["base_ref"]?.toString()?.takeIf(String::isNotBlank),
      headRef = checkpointMap["head_ref"]?.toString()?.takeIf(String::isNotBlank),
      workingTreeOwnedPaths = checkpointMap.optionalStringList("working_tree_owned_paths"),
    ),
  )
}

private fun malformed(field: String, reason: String): Nothing =
  throw InvalidFeatureTaskRuntimePlanningProjectionSchemaError(
    sourceLabel = "<$field>",
    reason = reason,
  )

private fun Map<String, Any?>.requireStringList(key: String): List<String> =
  optionalStringList(key).ifEmpty { throw malformed(key, "must contain at least one non-blank string") }

private fun Map<String, Any?>.requireString(key: String, label: String): String =
  firstString(key).ifBlank { throw malformed(label, "must be a non-blank string") }

private fun Map<String, Any?>.optionalStringList(key: String): List<String> {
  val raw = this[key] ?: return emptyList()
  val list = raw as? List<*> ?: throw malformed(key, "must be a list")
  return list.map { value ->
    value?.toString()?.trim()?.takeIf(String::isNotBlank)
      ?: throw malformed(key, "entries must be non-blank strings")
  }
}
