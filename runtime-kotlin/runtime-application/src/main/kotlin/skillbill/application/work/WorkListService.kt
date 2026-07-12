package skillbill.application.work

import me.tatarka.inject.annotations.Inject
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.model.WorkItem

data class WorkListResult(
  val dbPath: String,
  val work: List<WorkItem>,
)

@Inject
class WorkListService(
  private val database: DatabaseSessionFactory,
) {
  fun list(limit: Int? = null, dbOverride: String? = null): WorkListResult {
    require(limit == null || limit > 0) { "--limit must be a positive integer." }
    return database.read(dbOverride) { unitOfWork ->
      WorkListResult(
        dbPath = unitOfWork.dbPath.toString(),
        work = unitOfWork.workList.list(limit),
      )
    }
  }
}
