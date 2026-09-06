package skillbill.application.learning

import skillbill.review.model.ReviewRunId

import me.tatarka.inject.annotations.Inject
import skillbill.application.learning.model.AddLearningInput
import skillbill.application.learning.model.EditLearningInput
import skillbill.application.learning.model.LearningDeleteResult
import skillbill.application.learning.model.LearningListResult
import skillbill.application.learning.model.LearningRecordResult
import skillbill.application.learning.model.LearningResolveResult
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.learningEntry
import skillbill.learnings.learningEntrySessionJson
import skillbill.learnings.model.CreateLearningRequest
import skillbill.learnings.model.LearningScope
import skillbill.learnings.model.UpdateLearningRequest
import skillbill.ports.db.DatabaseSessionFactory

@Inject
class LearningService(private val database: DatabaseSessionFactory) {
  fun list(status: String): LearningListResult = database.read { unitOfWork ->
    val entries = unitOfWork.learnings.list(status).map(::learningEntry)
    LearningListResult(unitOfWork.dbPath.toString(), entries)
  }

  fun show(id: Int): LearningRecordResult = database.read { unitOfWork ->
    LearningRecordResult(unitOfWork.dbPath.toString(), learningEntry(unitOfWork.learnings.get(id)))
  }

  fun resolve(repo: String?, skill: String?, reviewSessionId: String?): LearningResolveResult =
    database.transaction { unitOfWork ->
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

  fun add(request: AddLearningInput): LearningRecordResult = database.transaction { unitOfWork ->
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

  fun edit(request: EditLearningInput): LearningRecordResult = database.transaction { unitOfWork ->
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

  fun setStatus(id: Int, status: String): LearningRecordResult = database.transaction { unitOfWork ->
    val record = unitOfWork.learnings.setStatus(id, status)
    LearningRecordResult(unitOfWork.dbPath.toString(), learningEntry(record))
  }

  fun delete(id: Int): LearningDeleteResult = database.transaction { unitOfWork ->
    unitOfWork.learnings.delete(id)
    LearningDeleteResult(unitOfWork.dbPath.toString(), id)
  }
}
