package skillbill.ports.persistence

import skillbill.ports.persistence.model.WorkItem

interface WorkListRepository {
  fun list(limit: Int? = null): List<WorkItem>
}
