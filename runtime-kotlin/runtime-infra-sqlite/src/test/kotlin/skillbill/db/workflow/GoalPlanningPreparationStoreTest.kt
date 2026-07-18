package skillbill.db.workflow

import skillbill.db.core.DatabaseRuntime
import skillbill.db.core.inImmediateTransaction
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.ports.persistence.model.GoalPlanningContractProvenance
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationProvenance
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationState
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Suppress("LargeClass")
class GoalPlanningPreparationStoreTest {
  @Test
  fun `bounded status covers fresh partial blocked and prepared planning`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)

      val fresh = store.boundedStatus("goal-1", listOf(1, 2))
      assertEquals(GoalPlanningStatusState.NOT_STARTED, fresh.state)
      assertEquals(1, fresh.currentPlanningSubtaskId)

      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      val partial = store.boundedStatus("goal-1", listOf(1, 2))
      assertEquals(GoalPlanningStatusState.PARTIALLY_PLANNED, partial.state)
      assertEquals(1, partial.plannedSubtaskCount)
      assertEquals(2, partial.currentPlanningSubtaskId)

      val blocked = store.boundedStatus("goal-1", listOf(1, 2), 2, "plan agent exhausted")
      assertEquals(GoalPlanningStatusState.BLOCKED, blocked.state)
      assertEquals("plan agent exhausted", blocked.reason)
      assertEquals(2, blocked.currentPlanningSubtaskId)

      store.checkpointSubtaskPlan(planCheckpoint(2, 1))
      val prepared = store.boundedStatus("goal-1", listOf(1, 2))
      assertEquals(GoalPlanningStatusState.PREPARED, prepared.state)
      assertNull(prepared.currentPlanningSubtaskId)
      assertNull(prepared.reason)
    }
  }

  @Test
  fun `bounded status rejects malformed governed and blocked state`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        store.boundedStatus("goal-1", listOf(1, 1))
      }
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        store.boundedStatus("goal-1", listOf(1, 2), blockedSubtaskId = 2)
      }
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        store.boundedStatus("goal-1", listOf(1, 2), 3, "unknown subtask")
      }
    }
  }

  @Test
  fun `normalized checkpoints list count and recover only against complete governed descriptors`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(2, 1))
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
      val descriptors = listOf(descriptor(1, 0), descriptor(2, 1), descriptor(3, 2))

      assertEquals(listOf(1, 2), store.listSubtaskPlansOrdered(identity(), descriptors).map { it.subtaskId })
      assertEquals(2, store.preparedPlanCount(identity(), descriptors))
      assertEquals(3, store.firstMissingPlan(identity(), descriptors))

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.listSubtaskPlansOrdered(
          identity(),
          descriptors.map { if (it.subtaskId == 2) it.copy(governedSubSpecPath = "wrong.md") else it },
        )
      }
    }
  }

  @Test
  fun `normalized checkpoints survive restart and incompatible soft-reset provenance fails loudly`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      assertNotNull(store.findSharedPreplan(identity()))
      val descriptors = listOf(descriptor(1, 0), descriptor(2, 1))
      assertEquals(1, store.preparedPlanCount(identity(), descriptors))
      assertEquals(2, store.firstMissingPlan(identity(), listOf(descriptor(1, 0), descriptor(2, 1))))
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.checkpointSharedPreplan(
          sharedCheckpoint().copy(provenance = provenance().copy(parentSpecHash = "f".repeat(64))),
        )
      }
    }
  }

  @Test
  fun `normalized hard-reset deletion rolls back atomically and remains deleted after restart`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(planCheckpoint(1, 0))

      assertFailsWith<IllegalStateException> {
        connection.inImmediateTransaction {
          store.deleteByGoal("goal-1")
          error("injected reset manifest failure")
        }
      }
      assertNotNull(store.findSharedPreplan(identity()))
      assertNotNull(store.findSubtaskPlan(identity(), 1, descriptor(1, 0).governedSubSpecPath))

      connection.inImmediateTransaction { store.deleteByGoal("goal-1") }
    }
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      assertNull(store.findSharedPreplan(identity()))
      assertEquals(0, store.preparedPlanCount(identity(), listOf(descriptor(1, 0))))
    }
  }

  @Test
  fun `normalized immutable replay conflict is rejected without changing the stored plan`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original = planCheckpoint(1, 0)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(original)

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.checkpointSubtaskPlan(
          original.copy(payloadSha256 = "f".repeat(64), planPayload = "changed-plan"),
        )
      }
      assertEquals(
        original.planPayload,
        store.findSubtaskPlan(identity(), 1, original.governedSubSpecPath)?.planPayload,
      )
    }
  }

  @Test
  fun `normalized alternate uniqueness conflicts fail loudly and preserve the original plan`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original = planCheckpoint(1, 0)
      store.checkpointSharedPreplan(sharedCheckpoint())
      store.checkpointSubtaskPlan(original)

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.checkpointSubtaskPlan(
          planCheckpoint(2, 1).copy(governedSubSpecPath = original.governedSubSpecPath),
        )
      }
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        store.checkpointSubtaskPlan(planCheckpoint(2, 0))
      }
      assertEquals(1, store.preparedPlanCount(identity(), listOf(descriptor(1, 0))))
    }
  }

  @Test
  fun `malformed normalized row fails loudly after restart`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.checkpointSharedPreplan(sharedCheckpoint())
      connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE goal_shared_preplans SET payload_sha256 = 'malformed'")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> {
        GoalPlanningPreparationStore(connection).findSharedPreplan(identity())
      }
    }
  }

  @Test
  fun `mark prepared stores and recovers a single subtask pair`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)

      store.markPrepared(record)

      val recovered = store.findByGoalAndSubtask("goal-1", 1)
      assertEquals(record.copy(createdAt = recovered!!.createdAt, updatedAt = recovered.updatedAt), recovered)
      assertEquals(listOf(1), store.listPreparedByGoalOrdered("goal-1").map { it.subtaskId })
      assertEquals(1, store.preparedCount("goal-1"))
      assertEquals(2, store.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2, 3)))
      val status = store.preparedStatus("goal-1", 1)
      assertEquals(GoalPlanningPreparationState.PREPARED, status?.preparationStatus)
      assertEquals(record.provenance, status?.provenance)
    }
  }

  @Test
  fun `marking the same pair again is an idempotent no-op`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)

      store.markPrepared(record)
      store.markPrepared(record)

      assertEquals(1, store.preparedCount("goal-1"))
      val recovered = store.findByGoalAndSubtask("goal-1", 1)
      assertEquals(record.preplanPayload, recovered?.preplanPayload)
    }
  }

  @Test
  fun `delete by goal removes only the selected parent preparation`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1))
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 2))
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-2", subtaskId = 1))

      assertEquals(2, store.deleteByGoal("goal-1"))

      assertEquals(0, store.preparedCount("goal-1"))
      assertEquals(1, store.preparedCount("goal-2"))
    }
  }

  @Test
  fun `marking a differing provenance pair fails loudly and leaves the stored pair unchanged`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1, subSpecHash = "sub-spec-A")
      store.markPrepared(original)

      val conflicting = original.copy(
        provenance = original.provenance.copy(subSpecHash = "sub-spec-B"),
        planPayload = """{"phase_id":"plan","v":2}""",
      )

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> { store.markPrepared(conflicting) }

      val recovered = store.findByGoalAndSubtask("goal-1", 1)
      assertEquals(original.provenance.subSpecHash, recovered?.provenance?.subSpecHash)
      assertEquals(original.planPayload, recovered?.planPayload)
    }
  }

  @Test
  fun `marking a same-key pair with a diverging repository identity fails loudly`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original = preparationRecord(
        parentGoalWorkflowId = "goal-1",
        subtaskId = 1,
        repositoryIdentity = "repo-root-realpath-v1:/repo-a",
      )
      store.markPrepared(original)

      val conflicting = original.copy(repositoryIdentity = "repo-root-realpath-v1:/repo-b")

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> { store.markPrepared(conflicting) }

      val recovered = store.findByGoalAndSubtask("goal-1", 1)
      assertEquals(original.repositoryIdentity, recovered?.repositoryIdentity)
      assertEquals(original.provenance, recovered?.provenance)
    }
  }

  @Test
  fun `marking a same-key pair with a diverging normalized issue key fails loudly`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val original = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
      store.markPrepared(original)

      val conflicting = original.copy(normalizedIssueKey = "SKILL-999")

      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> { store.markPrepared(conflicting) }

      val recovered = store.findByGoalAndSubtask("goal-1", 1)
      assertEquals(original.normalizedIssueKey, recovered?.normalizedIssueKey)
      assertEquals(original.provenance, recovered?.provenance)
    }
  }

  @Test
  fun `prepared pairs are isolated across parent goals`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1))
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-2", subtaskId = 1))

      assertEquals(1, store.preparedCount("goal-1"))
      assertEquals(1, store.preparedCount("goal-2"))
      assertEquals(2, store.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2)))
      assertNull(store.findByGoalAndSubtask("goal-1", 2))
    }
  }

  @Test
  fun `prepared pairs persist repository identity for repo-scoped recovery`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.markPrepared(
        preparationRecord(
          parentGoalWorkflowId = "goal-1",
          subtaskId = 1,
          repositoryIdentity = "repo-root-realpath-v1:/repo-a",
        ),
      )

      val recovered = store.findByGoalAndSubtask("goal-1", 1)
      assertEquals("repo-root-realpath-v1:/repo-a", recovered?.repositoryIdentity)
    }
  }

  @Test
  fun `ordered listing orders by subtask id and first missing walks ordered ids`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 3))
      store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1))

      assertEquals(listOf(1, 3), store.listPreparedByGoalOrdered("goal-1").map { it.subtaskId })
      assertEquals(2, store.preparedCount("goal-1"))
      assertEquals(2, store.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2, 3, 4)))
    }
  }

  @Test
  fun `first missing or incomplete returns null when every ordered id is prepared`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      listOf(1, 2, 3).forEach { subtaskId ->
        store.markPrepared(preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = subtaskId))
      }

      assertNull(store.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2, 3)))
      assertNull(store.firstMissingOrIncompleteSubtask("goal-1", emptyList()))
    }
  }

  @Test
  fun `restart recovery reopens the database and recovers the first incomplete subtask`() {
    val dbPath = tempDb()
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      GoalPlanningPreparationStore(connection).markPrepared(
        preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1),
      )
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      assertEquals(1, store.preparedCount("goal-1"))
      assertEquals(2, store.firstMissingOrIncompleteSubtask("goal-1", listOf(1, 2, 3)))
      assertNotNull(store.findByGoalAndSubtask("goal-1", 1))
    }
  }

  @Test
  fun `malformed envelope with wrong contract version is rejected`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(contractVersion = "0.2")

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(record) }
      assertNull(store.findByGoalAndSubtask("goal-1", 1))
    }
  }

  @Test
  fun `malformed envelope with non-positive subtask id is rejected`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 0)

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(record) }
    }
  }

  @Test
  fun `malformed envelope with pending status is rejected at the checkpoint seam`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val pending = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
        .copy(preparationStatus = GoalPlanningPreparationState.PENDING)

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(pending) }
    }
  }

  @Test
  fun `malformed envelope missing provenance hashes is rejected`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val record = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1).copy(
        provenance = GoalPlanningPreparationProvenance(
          parentSpecHash = "",
          subSpecHash = "sub",
          decompositionManifestHash = "manifest",
        ),
      )

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(record) }
    }
  }

  @Test
  fun `malformed envelope with a divergent phase output contract version is rejected at the store seam`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)
      val base = preparationRecord(parentGoalWorkflowId = "goal-1", subtaskId = 1)
      val record = base.copy(
        provenance = base.provenance.copy(phaseOutputContractVersion = "9.9"),
      )

      assertFailsWith<InvalidGoalPlanningPreparationSchemaError> { store.markPrepared(record) }
      assertNull(store.findByGoalAndSubtask("goal-1", 1))
    }
  }

  @Test
  fun `prepared status returns null for an unknown subtask`() {
    DatabaseRuntime.ensureDatabase(tempDb()).use { connection ->
      val store = GoalPlanningPreparationStore(connection)

      assertNull(store.preparedStatus("goal-1", 1))
    }
  }

  private fun tempDb(): Path =
    Files.createTempDirectory("runtime-kotlin-goal-planning-preparation").resolve("metrics.db")

  private fun identity() = GoalPlanningIdentity("goal-1", "SKILL-128", "repo-root-realpath-v1:/repository")

  private fun provenance() = GoalPlanningContractProvenance(
    parentSpecHash = "a".repeat(64),
    decompositionManifestHash = "b".repeat(64),
    planningContractId = skillbill.contracts.workflow.GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
  )

  private fun sharedCheckpoint() = SharedGoalPreplanCheckpoint(
    identity = identity(),
    provenance = provenance(),
    payloadSha256 = "c".repeat(64),
    preplanPayload = "preplan-payload",
  )

  private fun descriptor(subtaskId: Int, order: Int) = GovernedGoalSubtaskDescriptor(
    subtaskId,
    order,
    ".feature-specs/SKILL-128/spec_subtask_$subtaskId.md",
    "d".repeat(64),
  )

  private fun planCheckpoint(subtaskId: Int, order: Int): GoalSubtaskPlanCheckpoint {
    val descriptor = descriptor(subtaskId, order)
    return GoalSubtaskPlanCheckpoint(
      identity = identity(),
      subtaskId = subtaskId,
      manifestOrder = order,
      governedSubSpecPath = descriptor.governedSubSpecPath,
      subSpecHash = descriptor.subSpecHash,
      provenance = provenance(),
      payloadSha256 = "e".repeat(64),
      planPayload = "plan-$subtaskId",
    )
  }

  private fun preparationRecord(
    parentGoalWorkflowId: String,
    subtaskId: Int,
    repositoryIdentity: String = "repo-root-realpath-v1:/repository",
    subSpecHash: String = "sub-spec-default",
  ): GoalPlanningPreparationRecord = GoalPlanningPreparationRecord(
    parentGoalWorkflowId = parentGoalWorkflowId,
    normalizedIssueKey = "SKILL-128",
    repositoryIdentity = repositoryIdentity,
    subtaskId = subtaskId,
    governedSubSpecPath = ".feature-specs/SKILL-128/spec_subtask_$subtaskId.md",
    preparationStatus = GoalPlanningPreparationState.PREPARED,
    provenance = GoalPlanningPreparationProvenance(
      parentSpecHash = "parent-spec-$parentGoalWorkflowId",
      subSpecHash = subSpecHash,
      decompositionManifestHash = "manifest-$parentGoalWorkflowId",
    ),
    preplanPayload = """{"phase_id":"preplan"}""",
    planPayload = """{"phase_id":"plan"}""",
  )
}
