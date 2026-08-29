package skillbill.ports.work

import skillbill.ports.work.model.WorkItem

interface WorkListRepository {
  fun list(limit: Int? = null): List<WorkItem>
}
