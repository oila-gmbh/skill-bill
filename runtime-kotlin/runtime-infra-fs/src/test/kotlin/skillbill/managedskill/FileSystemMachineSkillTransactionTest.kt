package skillbill.managedskill

import skillbill.managedskill.model.FileSystemIdentity
import skillbill.managedskill.model.IdentityCapability
import skillbill.managedskill.model.MachineSkillApplyResult
import skillbill.managedskill.model.MachineSkillMutation
import skillbill.managedskill.model.MachineSkillMutationKind
import skillbill.managedskill.model.MachineSkillMutationPlan
import skillbill.managedskill.model.MachineSkillOperation
import skillbill.managedskill.model.MachineSkillOutcome
import skillbill.managedskill.model.MachineSkillPreconditions
import skillbill.managedskill.model.MachineSkillResourceKind
import skillbill.managedskill.model.NoFollowEntryKind
import skillbill.managedskill.model.PathObservation
import skillbill.managedskill.model.PreparedMachineSkillMutation
import skillbill.managedskill.model.SymlinkCapability
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSystemMachineSkillTransactionTest {
  @Test
  fun `unavailable symlink capability blocks before the first write with Windows guidance`() {
    val home = Files.createTempDirectory("machine-skill-windows-preflight")
    val targetRoot = home.resolve("target-without-parent")
    val link = targetRoot.resolve("demo")
    val snapshot = home.resolve(".skill-bill/installed-skills/demo-hash")
    val observation = PathObservation(
      link,
      NoFollowEntryKind.ABSENT,
      FileSystemIdentity(IdentityCapability.UNAVAILABLE),
    )
    Files.createDirectories(home.resolve(".skill-bill/managed-skills/demo"))
    val transaction = FileSystemMachineSkillTransaction(
      home,
      listOf(targetRoot),
      NativeSymlinkCapabilityProbe { SymlinkCapability.UNAVAILABLE },
    )
    val current = transaction.currentPreconditions(emptyPlan(observation))
    val plan = MachineSkillMutationPlan(
      MachineSkillMutationKind.INSTALL,
      "demo",
      listOf(
        MachineSkillMutation(
          MachineSkillResourceKind.AGENT_LINK,
          MachineSkillOperation.CREATE,
          MachineSkillOutcome.CREATE,
          link,
          observation,
          snapshot,
        ),
      ),
      current,
    )

    val blocked = assertIs<MachineSkillApplyResult.Blocked>(
      transaction.apply(PreparedMachineSkillMutation(plan)),
    )
    assertTrue(blocked.reason.contains("Developer Mode"))
    assertFalse(Files.exists(link))
    assertFalse(Files.exists(targetRoot))
  }

  private fun emptyPlan(observation: PathObservation): MachineSkillMutationPlan {
    val preconditions = MachineSkillPreconditions(
      observations = listOf(observation),
      recordDigest = null,
      recordContractVersion = null,
      activeHash = null,
      candidateBundle = null,
      targetIdentities = emptySet(),
      ownershipProofs = emptyMap(),
      snapshotReferences = emptySet(),
      referenceDiscoveryComplete = true,
      symlinkCapability = SymlinkCapability.UNAVAILABLE,
    )
    return MachineSkillMutationPlan(MachineSkillMutationKind.INSTALL, "demo", emptyList(), preconditions)
  }
}
