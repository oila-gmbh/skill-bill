package skillbill.infrastructure.sqlite.workflow

import skillbill.infrastructure.sqlite.core.DatabaseRuntime
import skillbill.infrastructure.sqlite.inReadTransaction
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.AuditRepairCycleRepository
import skillbill.ports.featuretask.model.AuditRepairStatusSnapshot
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairLaunchBinding
import skillbill.workflow.taskruntime.model.AuditRepairRevision
import java.time.Clock

class SqliteAuditRepairCycleRepository(
  private val databaseSessionFactory: DatabaseSessionFactory,
  private val clock: Clock,
) : AuditRepairCycleRepository {
  override fun validateStageOwner(identity: AuditRepairIdentity) {
    withStageOwner(identity) { Unit }
  }

  override fun <T> withStageOwner(identity: AuditRepairIdentity, action: (AuditRepairCycleRepository) -> T): T =
    DatabaseRuntime.openDbAt(databaseSessionFactory.resolveDbPath()).use { database ->
      AuditRepairCycleStore(database.connection, clock).withStageOwner(identity, action)
    }

  override fun start(cycle: AuditRepairCycle): AuditRepairCycle =
    DatabaseRuntime.openDbAt(databaseSessionFactory.resolveDbPath()).use { database ->
      AuditRepairCycleStore(database.connection, clock).start(cycle)
    }

  override fun find(workflowId: String, cycleId: String): AuditRepairCycle? =
    DatabaseRuntime.openReadDbIfPresentAt(databaseSessionFactory.resolveDbPath())?.use { database ->
      AuditRepairCycleStore(database.connection, clock).find(workflowId, cycleId)
    }

  override fun advance(
    identity: AuditRepairIdentity,
    expectedRevision: Int,
    revision: AuditRepairRevision,
  ): AuditRepairCycle = DatabaseRuntime.openDbAt(databaseSessionFactory.resolveDbPath()).use { database ->
    AuditRepairCycleStore(database.connection, clock).advance(identity, expectedRevision, revision)
  }

  override fun attachCheckpoint(
    identity: AuditRepairIdentity,
    expectedRevision: Int,
    checkpointIntent: String,
    checkpoint: AuditRepairCheckpoint,
  ): AuditRepairCycle = DatabaseRuntime.openDbAt(databaseSessionFactory.resolveDbPath()).use { database ->
    AuditRepairCycleStore(database.connection, clock).attachCheckpoint(
      identity,
      expectedRevision,
      checkpointIntent,
      checkpoint,
    )
  }

  override fun findForAttempt(workflowId: String, auditAttempt: Int): AuditRepairCycle? =
    DatabaseRuntime.openReadDbIfPresentAt(databaseSessionFactory.resolveDbPath())?.use { database ->
      AuditRepairCycleStore(database.connection, clock).findForAttempt(workflowId, auditAttempt)
    }

  override fun statusSnapshot(workflowId: String): AuditRepairStatusSnapshot? =
    DatabaseRuntime.openReadDbIfPresentAt(databaseSessionFactory.resolveDbPath())?.use { database ->
      database.connection.inReadTransaction(databaseSessionFactory.resolveDbPath()) {
        AuditRepairCycleStore(database.connection, clock).statusSnapshot(workflowId)
      }
    }

  override fun findActive(workflowId: String): AuditRepairCycle? =
    DatabaseRuntime.openReadDbIfPresentAt(databaseSessionFactory.resolveDbPath())?.use { database ->
      AuditRepairCycleStore(database.connection, clock).findActive(workflowId)
    }

  override fun bindLaunch(binding: AuditRepairLaunchBinding): AuditRepairLaunchBinding =
    DatabaseRuntime.openDbAt(databaseSessionFactory.resolveDbPath()).use { database ->
      AuditRepairCycleStore(database.connection, clock).bindLaunch(binding)
    }

  override fun recordProviderSession(
    identity: AuditRepairIdentity,
    providerSessionId: String,
  ): AuditRepairLaunchBinding = DatabaseRuntime.openDbAt(databaseSessionFactory.resolveDbPath()).use { database ->
    AuditRepairCycleStore(database.connection, clock).recordProviderSession(identity, providerSessionId)
  }

  override fun findLaunchBinding(
    workflowId: String,
    executionId: String,
    requestedSessionId: String,
  ): AuditRepairLaunchBinding? =
    DatabaseRuntime.openReadDbIfPresentAt(databaseSessionFactory.resolveDbPath())?.use { database ->
      AuditRepairCycleStore(database.connection, clock).findLaunchBinding(workflowId, executionId, requestedSessionId)
    }
}
