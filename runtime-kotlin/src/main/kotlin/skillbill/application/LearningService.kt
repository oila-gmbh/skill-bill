package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.db.DatabaseRuntime
import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningStore
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.learningEntry
import skillbill.learnings.learningEntrySessionJson

@Inject
class LearningService(private val context: RuntimeContext) {
  fun list(status: String, dbOverride: String?): LearningListResult {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val entries = LearningStore.listLearnings(openDb.connection, status).map(::learningEntry)
      return LearningListResult(openDb.dbPath.toString(), entries)
    }
  }

  fun show(id: Int, dbOverride: String?): LearningRecordResult {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val record = LearningStore.getLearning(openDb.connection, id)
      return LearningRecordResult(openDb.dbPath.toString(), learningEntry(record))
    }
  }

  fun resolve(repo: String?, skill: String?, reviewSessionId: String?, dbOverride: String?): LearningResolveResult {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val (repoScopeKey, skillName, rows) = LearningsRuntime.resolveLearnings(openDb.connection, repo, skill)
      val entries = rows.map(::learningEntry)
      reviewSessionId?.takeIf(String::isNotBlank)?.let {
        LearningsRuntime.saveSessionLearnings(openDb.connection, it, learningEntrySessionJson(skillName, entries))
      }
      return LearningResolveResult(
        dbPath = openDb.dbPath.toString(),
        repoScopeKey = repoScopeKey,
        skillName = skillName,
        reviewSessionId = reviewSessionId,
        scopePrecedence = LearningScope.precedence,
        learnings = entries,
      )
    }
  }

  fun add(request: AddLearningInput, dbOverride: String?): LearningRecordResult {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val learningId =
        LearningStore.addLearning(
          openDb.connection,
          CreateLearningRequest(
            request.scope,
            request.scopeKey,
            request.title,
            request.rule,
            request.reason,
            request.fromRun,
            request.fromFinding,
          ),
        )
      return LearningRecordResult(
        openDb.dbPath.toString(),
        learningEntry(LearningStore.getLearning(openDb.connection, learningId)),
      )
    }
  }

  fun edit(request: EditLearningInput, dbOverride: String?): LearningRecordResult {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val record =
        LearningStore.editLearning(
          openDb.connection,
          skillbill.learnings.UpdateLearningRequest(
            request.id,
            request.scope,
            request.scopeKey,
            request.title,
            request.rule,
            request.reason,
          ),
        )
      return LearningRecordResult(openDb.dbPath.toString(), learningEntry(record))
    }
  }

  fun setStatus(id: Int, status: String, dbOverride: String?): LearningRecordResult {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val record = LearningStore.setLearningStatus(openDb.connection, id, status)
      return LearningRecordResult(openDb.dbPath.toString(), learningEntry(record))
    }
  }

  fun delete(id: Int, dbOverride: String?): LearningDeleteResult {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      LearningStore.deleteLearning(openDb.connection, id)
      return LearningDeleteResult(openDb.dbPath.toString(), id)
    }
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
