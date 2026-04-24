package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.learningEntry
import skillbill.learnings.learningEntrySessionJson
import skillbill.ports.persistence.DatabaseSessionFactory

@Inject
class LearningService(private val database: DatabaseSessionFactory) {
  fun list(status: String, dbOverride: String?): LearningListResult = database.read(dbOverride) { unitOfWork ->
    val entries = unitOfWork.learnings.list(status).map(::learningEntry)
    LearningListResult(unitOfWork.dbPath.toString(), entries)
  }

  fun show(id: Int, dbOverride: String?): LearningRecordResult = database.read(dbOverride) { unitOfWork ->
    LearningRecordResult(unitOfWork.dbPath.toString(), learningEntry(unitOfWork.learnings.get(id)))
  }

  fun resolve(repo: String?, skill: String?, reviewSessionId: String?, dbOverride: String?): LearningResolveResult =
    database.transaction(dbOverride) { unitOfWork ->
      val resolution = unitOfWork.learnings.resolve(repo, skill)
      val entries = resolution.records.map(::learningEntry)
      reviewSessionId?.takeIf(String::isNotBlank)?.let {
        unitOfWork.learnings.saveSessionLearnings(it, learningEntrySessionJson(resolution.skillName, entries))
      }
      LearningResolveResult(
        dbPath = unitOfWork.dbPath.toString(),
        repoScopeKey = resolution.repoScopeKey,
        skillName = resolution.skillName,
        reviewSessionId = reviewSessionId,
        scopePrecedence = LearningScope.precedence,
        learnings = entries,
      )
    }

  fun add(request: AddLearningInput, dbOverride: String?): LearningRecordResult =
    database.transaction(dbOverride) { unitOfWork ->
      val createRequest =
        CreateLearningRequest(
          request.scope,
          request.scopeKey,
          request.title,
          request.rule,
          request.reason,
          request.fromRun,
          request.fromFinding,
        )
      val sourceReference =
        LearningsRuntime.validateLearningSourceReference(
          createRequest.sourceReviewRunId,
          createRequest.sourceFindingId,
        )
      val sourceValidation =
        LearningsRuntime.validateLearningSource(
          sourceReference = sourceReference,
          sourceFindingExists =
          unitOfWork.reviews.findingExists(sourceReference.reviewRunId, sourceReference.findingId),
          latestRejectedOutcome =
          unitOfWork.reviews.latestRejectedLearningSourceOutcome(
            sourceReference.reviewRunId,
            sourceReference.findingId,
          ),
        )
      val learningId =
        unitOfWork.learnings.add(createRequest, sourceValidation)
      LearningRecordResult(
        unitOfWork.dbPath.toString(),
        learningEntry(unitOfWork.learnings.get(learningId)),
      )
    }

  fun edit(request: EditLearningInput, dbOverride: String?): LearningRecordResult =
    database.transaction(dbOverride) { unitOfWork ->
      val record =
        unitOfWork.learnings.edit(
          skillbill.learnings.UpdateLearningRequest(
            request.id,
            request.scope,
            request.scopeKey,
            request.title,
            request.rule,
            request.reason,
          ),
        )
      LearningRecordResult(unitOfWork.dbPath.toString(), learningEntry(record))
    }

  fun setStatus(id: Int, status: String, dbOverride: String?): LearningRecordResult =
    database.transaction(dbOverride) { unitOfWork ->
      val record = unitOfWork.learnings.setStatus(id, status)
      LearningRecordResult(unitOfWork.dbPath.toString(), learningEntry(record))
    }

  fun delete(id: Int, dbOverride: String?): LearningDeleteResult = database.transaction(dbOverride) { unitOfWork ->
    unitOfWork.learnings.delete(id)
    LearningDeleteResult(unitOfWork.dbPath.toString(), id)
  }
}

data class AddLearningInput(
  val scope: LearningScope,
  val scopeKey: String,
  val title: String,
  val rule: String,
  val reason: String,
  val fromRun: String,
  val fromFinding: String,
)

data class EditLearningInput(
  val id: Int,
  val scope: LearningScope?,
  val scopeKey: String?,
  val title: String?,
  val rule: String?,
  val reason: String?,
)
