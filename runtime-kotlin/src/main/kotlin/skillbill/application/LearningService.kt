package skillbill.application

import me.tatarka.inject.annotations.Inject
import skillbill.RuntimeContext
import skillbill.db.DatabaseRuntime
import skillbill.learnings.CreateLearningRequest
import skillbill.learnings.LearningScope
import skillbill.learnings.LearningStore
import skillbill.learnings.LearningsRuntime
import skillbill.learnings.learningPayload
import skillbill.learnings.learningSessionJson

@Inject
class LearningService(private val context: RuntimeContext) {
  fun list(status: String, dbOverride: String?): Map<String, Any?> {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val entries = LearningStore.listLearnings(openDb.connection, status).map(::learningPayload)
      return linkedMapOf("db_path" to openDb.dbPath.toString(), "learnings" to entries)
    }
  }

  fun show(id: Int, dbOverride: String?): Map<String, Any?> {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val record = LearningStore.getLearning(openDb.connection, id)
      return learningRecordPayload(openDb.dbPath.toString(), record)
    }
  }

  fun resolve(repo: String?, skill: String?, reviewSessionId: String?, dbOverride: String?): Map<String, Any?> {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val (repoScopeKey, skillName, rows) = LearningsRuntime.resolveLearnings(openDb.connection, repo, skill)
      val payloadEntries = rows.map(::learningPayload)
      reviewSessionId?.takeIf(String::isNotBlank)?.let {
        LearningsRuntime.saveSessionLearnings(openDb.connection, it, learningSessionJson(skillName, payloadEntries))
      }
      return learningsResolvePayload(openDb.dbPath.toString(), repoScopeKey, skillName, reviewSessionId, payloadEntries)
    }
  }

  fun add(request: AddLearningInput, dbOverride: String?): Map<String, Any?> {
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
      return learningRecordPayload(openDb.dbPath.toString(), LearningStore.getLearning(openDb.connection, learningId))
    }
  }

  fun edit(request: EditLearningInput, dbOverride: String?): Map<String, Any?> {
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
      return learningRecordPayload(openDb.dbPath.toString(), record)
    }
  }

  fun setStatus(id: Int, status: String, dbOverride: String?): Map<String, Any?> {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      val record = LearningStore.setLearningStatus(openDb.connection, id, status)
      return learningRecordPayload(openDb.dbPath.toString(), record)
    }
  }

  fun delete(id: Int, dbOverride: String?): Map<String, Any?> {
    DatabaseRuntime.openDb(dbOverride, context.environment, context.userHome).use { openDb ->
      LearningStore.deleteLearning(openDb.connection, id)
      return linkedMapOf("db_path" to openDb.dbPath.toString(), "deleted_learning_id" to id)
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
