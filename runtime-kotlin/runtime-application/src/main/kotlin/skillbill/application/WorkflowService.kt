package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.WorkflowFamilyKind
import skillbill.application.model.WorkflowUpdateRequest
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.random.Random

@Inject
class WorkflowService(
  private val database: DatabaseSessionFactory,
) {
  fun open(
    kind: WorkflowFamilyKind,
    sessionId: String = "",
    currentStepId: String? = null,
    dbOverride: String? = null,
  ): Map<String, Any?> {
    val family = kind.workflowFamily()
    val stepId = currentStepId ?: family.definition.defaultInitialStepId
    val workflowId = generateWorkflowId(family.definition.workflowIdPrefix)
    WorkflowEngine.validateOpen(family.definition, stepId)?.let { error ->
      return errorPayload(workflowId, error)
    }
    return database.transaction(dbOverride) { unitOfWork ->
      val record = WorkflowEngine.openRecord(family.definition, workflowId, sessionId, stepId)
      family.save(unitOfWork.workflowStates, record)
      val saved = family.get(unitOfWork.workflowStates, workflowId) ?: record
      ok(WorkflowEngine.fullPayload(family.definition, saved), unitOfWork)
    }
  }

  fun update(kind: WorkflowFamilyKind, request: WorkflowUpdateRequest, dbOverride: String? = null): Map<String, Any?> {
    val family = kind.workflowFamily()
    val input = request.toWorkflowUpdateInput()
    WorkflowEngine.validateUpdate(family.definition, input)?.let { error ->
      return errorPayload(request.workflowId, error)
    }
    return database.transaction(dbOverride) { unitOfWork ->
      val existing = family.get(unitOfWork.workflowStates, request.workflowId)
        ?: return@transaction unknownWorkflowPayload(request.workflowId)
      family.save(unitOfWork.workflowStates, WorkflowEngine.updateRecord(family.definition, existing, input))
      val updated = family.get(unitOfWork.workflowStates, request.workflowId) ?: existing
      ok(WorkflowEngine.fullPayload(family.definition, updated), unitOfWork)
    }
  }

  fun get(kind: WorkflowFamilyKind, workflowId: String, dbOverride: String? = null): Map<String, Any?> =
    database.read(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      val record = family.get(unitOfWork.workflowStates, workflowId)
        ?: return@read unknownWorkflowPayload(workflowId, unitOfWork.dbPath.toString())
      ok(WorkflowEngine.fullPayload(family.definition, record), unitOfWork)
    }

  fun list(kind: WorkflowFamilyKind, limit: Int = DEFAULT_LIST_LIMIT, dbOverride: String? = null): Map<String, Any?> =
    database.read(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      val rows = family.list(unitOfWork.workflowStates, limit)
      linkedMapOf(
        "status" to "ok",
        "db_path" to unitOfWork.dbPath.toString(),
        "workflow_count" to rows.size,
        "workflows" to rows.map { WorkflowEngine.summaryPayload(family.definition, it) },
      )
    }

  fun latest(kind: WorkflowFamilyKind, dbOverride: String? = null): Map<String, Any?> =
    database.read(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      val record = family.latest(unitOfWork.workflowStates)
        ?: return@read linkedMapOf(
          "status" to "error",
          "error" to "No ${family.humanName} workflows found.",
          "db_path" to unitOfWork.dbPath.toString(),
        )
      ok(WorkflowEngine.summaryPayload(family.definition, record), unitOfWork)
    }

  fun resume(kind: WorkflowFamilyKind, workflowId: String, dbOverride: String? = null): Map<String, Any?> =
    database.read(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      val record = family.get(unitOfWork.workflowStates, workflowId)
        ?: return@read unknownWorkflowPayload(workflowId, unitOfWork.dbPath.toString())
      ok(WorkflowEngine.resumePayload(family.definition, record), unitOfWork)
    }

  fun continueWorkflow(kind: WorkflowFamilyKind, workflowId: String, dbOverride: String? = null): Map<String, Any?> =
    database.transaction(dbOverride) { unitOfWork ->
      val family = kind.workflowFamily()
      var record = family.get(unitOfWork.workflowStates, workflowId)
        ?: return@transaction unknownWorkflowPayload(workflowId, unitOfWork.dbPath.toString())
      val sessionSummary = family.sessionSummary(unitOfWork.workflowStates, record.sessionId.orEmpty())
      var decision = WorkflowEngine.continueDecision(family.definition, record, sessionSummary)
      if (decision.shouldReopen) {
        val continueStatus = decision.payload["continue_status"]
        val reopened = WorkflowEngine.updateRecord(family.definition, record, decision.toReopenInput(record.sessionId))
        family.save(unitOfWork.workflowStates, reopened)
        record = family.get(unitOfWork.workflowStates, workflowId) ?: reopened
        val refreshedPayload =
          LinkedHashMap(
            WorkflowEngine.continueDecision(family.definition, record, sessionSummary).payload,
          )
        refreshedPayload["continue_status"] = continueStatus
        decision = decision.copy(payload = refreshedPayload)
      }
      continuePayload(decision.payload, unitOfWork)
    }
}

private const val DEFAULT_LIST_LIMIT: Int = 20
private const val WORKFLOW_ID_SUFFIX_LENGTH: Int = 4
private const val SUFFIX_CHARS: String = "abcdefghijklmnopqrstuvwxyz0123456789"

private fun WorkflowUpdateRequest.toWorkflowUpdateInput(): WorkflowUpdateInput = WorkflowUpdateInput(
  workflowStatus = workflowStatus,
  currentStepId = currentStepId,
  stepUpdates = stepUpdates,
  artifactsPatch = artifactsPatch,
  sessionId = sessionId,
)

private fun skillbill.workflow.model.WorkflowContinueDecision.toReopenInput(sessionId: String): WorkflowUpdateInput =
  WorkflowUpdateInput(
    workflowStatus = "running",
    currentStepId = resumeStepId,
    stepUpdates =
    listOf(
      mapOf(
        "step_id" to resumeStepId,
        "status" to "running",
        "attempt_count" to nextAttemptCount,
      ),
    ),
    artifactsPatch = null,
    sessionId = sessionId,
  )

private fun ok(payload: Map<String, Any?>, unitOfWork: UnitOfWork): Map<String, Any?> = LinkedHashMap(payload).apply {
  put("status", "ok")
  put("db_path", unitOfWork.dbPath.toString())
}

private fun unknownWorkflowPayload(workflowId: String, dbPath: String? = null): Map<String, Any?> =
  LinkedHashMap<String, Any?>().apply {
    put("status", "error")
    put("workflow_id", workflowId)
    put("error", "Unknown workflow_id '$workflowId'.")
    dbPath?.let { put("db_path", it) }
  }

private fun errorPayload(workflowId: String, error: String): Map<String, Any?> =
  linkedMapOf("status" to "error", "workflow_id" to workflowId, "error" to error)

private fun continuePayload(payload: Map<String, Any?>, unitOfWork: UnitOfWork): Map<String, Any?> =
  LinkedHashMap(payload).apply {
    put("db_path", unitOfWork.dbPath.toString())
    if (this["continue_status"] == "blocked") {
      val missingArtifacts = this["missing_artifacts"] as? List<*> ?: emptyList<Any?>()
      put("status", "error")
      put(
        "error",
        "Cannot continue workflow until the missing artifacts are restored: " +
          missingArtifacts.joinToString { it.toString() },
      )
    } else {
      put("status", "ok")
    }
  }

private fun generateWorkflowId(prefix: String): String {
  val now = OffsetDateTime.now(ZoneOffset.UTC)
  val suffix = (1..WORKFLOW_ID_SUFFIX_LENGTH).map { SUFFIX_CHARS[Random.nextInt(SUFFIX_CHARS.length)] }
    .joinToString("")
  return "$prefix-${now.year}${now.monthValue.twoDigits()}${now.dayOfMonth.twoDigits()}-" +
    "${now.hour.twoDigits()}${now.minute.twoDigits()}${now.second.twoDigits()}-$suffix"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')

private fun WorkflowFamilyKind.workflowFamily(): WorkflowFamily = when (this) {
  WorkflowFamilyKind.IMPLEMENT -> WorkflowFamily.IMPLEMENT
  WorkflowFamilyKind.VERIFY -> WorkflowFamily.VERIFY
}

private enum class WorkflowFamily(
  val definition: WorkflowDefinition,
  val humanName: String,
) {
  IMPLEMENT(FeatureImplementWorkflowDefinition.definition, "feature-implement"),
  VERIFY(FeatureVerifyWorkflowDefinition.definition, "feature-verify"),
  ;

  fun save(repository: WorkflowStateRepository, record: WorkflowStateSnapshot) {
    when (this) {
      IMPLEMENT -> repository.saveFeatureImplementWorkflow(record.toRecord())
      VERIFY -> repository.saveFeatureVerifyWorkflow(record.toRecord())
    }
  }

  fun get(repository: WorkflowStateRepository, workflowId: String): WorkflowStateSnapshot? = when (this) {
    IMPLEMENT -> repository.getFeatureImplementWorkflow(workflowId)
    VERIFY -> repository.getFeatureVerifyWorkflow(workflowId)
  }?.toSnapshot()

  fun list(repository: WorkflowStateRepository, limit: Int): List<WorkflowStateSnapshot> = when (this) {
    IMPLEMENT -> repository.listFeatureImplementWorkflows(limit)
    VERIFY -> repository.listFeatureVerifyWorkflows(limit)
  }.map(WorkflowStateRecord::toSnapshot)

  fun latest(repository: WorkflowStateRepository): WorkflowStateSnapshot? = when (this) {
    IMPLEMENT -> repository.latestFeatureImplementWorkflow()
    VERIFY -> repository.latestFeatureVerifyWorkflow()
  }?.toSnapshot()

  fun sessionSummary(repository: WorkflowStateRepository, sessionId: String): Map<String, Any?> {
    if (sessionId.isBlank()) {
      return emptyMap()
    }
    return when (this) {
      IMPLEMENT -> repository.getFeatureImplementSessionSummary(sessionId)?.toPayload().orEmpty()
      VERIFY -> repository.getFeatureVerifySessionSummary(sessionId)?.toPayload().orEmpty()
    }
  }
}
