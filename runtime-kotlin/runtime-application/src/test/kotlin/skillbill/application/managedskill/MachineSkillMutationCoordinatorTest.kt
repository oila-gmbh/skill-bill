package skillbill.application.managedskill

import kotlinx.coroutines.runBlocking
import skillbill.managedskill.model.MachineSkillApplyResult
import skillbill.managedskill.model.MachineSkillMutationKind
import skillbill.managedskill.model.MachineSkillMutationPlan
import skillbill.managedskill.model.MachineSkillPreconditions
import skillbill.managedskill.model.PreparedMachineSkillMutation
import skillbill.managedskill.model.SymlinkCapability
import skillbill.ports.managedskill.MachineSkillTransactionPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MachineSkillMutationCoordinatorTest {
  @Test
  fun `stale plan is rejected before transaction apply`() = runBlocking {
    val expected = preconditions(recordDigest = "before")
    val transaction = RecordingTransaction(preconditions(recordDigest = "after"))
    val result = MachineSkillMutationCoordinator(transaction).apply(prepared(expected))

    val stale = assertIs<MachineSkillApplyResult.Stale>(result)
    assertEquals(listOf("record"), stale.failures.map { it.code })
    assertEquals(0, transaction.applyCount)
  }

  @Test
  fun `matching plan delegates exactly once`() = runBlocking {
    val expected = preconditions(recordDigest = "stable")
    val transaction = RecordingTransaction(expected)
    val mutation = prepared(expected)

    assertEquals(
      MachineSkillApplyResult.Applied(mutation.plan.planId),
      MachineSkillMutationCoordinator(transaction).apply(mutation),
    )
    assertEquals(1, transaction.applyCount)
  }

  private fun prepared(preconditions: MachineSkillPreconditions) = PreparedMachineSkillMutation(
    MachineSkillMutationPlan(MachineSkillMutationKind.REPAIR, "demo", emptyList(), preconditions),
  )

  private fun preconditions(recordDigest: String) = MachineSkillPreconditions(
    observations = emptyList(),
    recordDigest = recordDigest,
    recordContractVersion = "0.1",
    activeHash = "hash",
    candidateBundle = null,
    targetIdentities = setOf("codex:/tmp/codex"),
    ownershipProofs = emptyMap(),
    snapshotReferences = emptySet(),
    referenceDiscoveryComplete = true,
    symlinkCapability = SymlinkCapability.AVAILABLE,
  )

  private class RecordingTransaction(
    private val current: MachineSkillPreconditions,
  ) : MachineSkillTransactionPort {
    var applyCount = 0

    override fun currentPreconditions(plan: MachineSkillMutationPlan) = current

    override fun apply(prepared: PreparedMachineSkillMutation): MachineSkillApplyResult {
      applyCount += 1
      return MachineSkillApplyResult.Applied(prepared.plan.planId)
    }

    override fun recoverIncompleteTransactions() = emptyList<String>()
  }
}
