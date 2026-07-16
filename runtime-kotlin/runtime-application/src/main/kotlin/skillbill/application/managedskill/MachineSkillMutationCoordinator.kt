package skillbill.application.managedskill

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import skillbill.managedskill.model.MachineSkillApplyResult
import skillbill.managedskill.model.MachineSkillMutationPlan
import skillbill.managedskill.model.MachineSkillPreconditions
import skillbill.managedskill.model.StalePrecondition
import skillbill.managedskill.model.PreparedMachineSkillMutation
import skillbill.ports.managedskill.MachineSkillTransactionPort

class MachineSkillMutationCoordinator(private val transaction: MachineSkillTransactionPort) {
  suspend fun apply(prepared: PreparedMachineSkillMutation): MachineSkillApplyResult {
    val plan = prepared.plan
    val current = transaction.currentPreconditions(plan)
    val stale = compare(plan.preconditions, current)
    if (stale.isNotEmpty()) return MachineSkillApplyResult.Stale(stale)
    return try {
      transaction.apply(prepared)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      withContext(NonCancellable) { transaction.recoverIncompleteTransactions() }
      throw cancelled
    }
  }

  private fun compare(
    expected: MachineSkillPreconditions,
    actual: MachineSkillPreconditions,
  ): List<StalePrecondition> {
    val failures = mutableListOf<StalePrecondition>()
    fun check(code: String, expectedValue: Any?, actualValue: Any?) {
      if (expectedValue != actualValue) {
        failures += StalePrecondition(code, null, expectedValue.toString(), actualValue.toString())
      }
    }
    check(
      "record",
      expected.recordDigest to expected.recordContractVersion,
      actual.recordDigest to actual.recordContractVersion,
    )
    check("active-hash", expected.activeHash, actual.activeHash)
    check("candidate", expected.candidateBundle, actual.candidateBundle)
    check("targets", expected.targetIdentities, actual.targetIdentities)
    check("ownership", expected.ownershipProofs, actual.ownershipProofs)
    check(
      "references",
      expected.snapshotReferences to expected.referenceDiscoveryComplete,
      actual.snapshotReferences to actual.referenceDiscoveryComplete,
    )
    check("capability", expected.symlinkCapability, actual.symlinkCapability)
    expected.observations.forEach { before ->
      val now = actual.observations.find { it.path == before.path }
      if (before != now) failures += StalePrecondition("path", before.path, before.toString(), now.toString())
    }
    return failures
  }
}
