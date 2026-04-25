package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.AddLearningInput
import skillbill.application.model.EditLearningInput
import skillbill.application.model.LearningDeleteResult
import skillbill.application.model.LearningListResult
import skillbill.application.model.LearningRecordResult
import skillbill.application.model.LearningResolveResult
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.learningEntry
import skillbill.learnings.learningEntrySessionJson
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.UpdateLearningRequest
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
          UpdateLearningRequest(
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
