package skillbill.application.managedskill

import kotlinx.coroutines.runBlocking
import skillbill.managedskill.FileSystemMachineSkillTransaction
import skillbill.managedskill.FileSystemMachineSkillWorkspace
import skillbill.managedskill.model.AgentSkillTargetId
import skillbill.managedskill.model.ConfirmMachineSkillDeleteRequest
import skillbill.managedskill.model.DeleteMachineSkillRequest
import skillbill.managedskill.model.EditMachineSkillRequest
import skillbill.managedskill.model.InstallMachineSkillRequest
import skillbill.managedskill.model.MachineSkillInstallDecision
import skillbill.managedskill.model.SaveMachineSkillEditRequest
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MachineSkillEndToEndTest {
  @Test
  fun `install update edit and delete keep all targets on one snapshot and preserve unmanaged files`() = runBlocking {
    val home = Files.createTempDirectory("machine-skill-e2e")
    val codexRoot = Files.createDirectories(home.resolve(".codex/skills"))
    val claudeRoot = Files.createDirectories(home.resolve(".claude/skills"))
    Files.createDirectories(home.resolve(".skill-bill/managed-skills"))
    Files.createDirectories(home.resolve(".skill-bill/installed-skills"))
    val source = Files.createDirectories(home.resolve("incoming/demo"))
    source.resolve("SKILL.md").writeText(markdown("first"))
    source.resolve("notes.txt").writeText("support")
    val unmanaged = Files.createDirectories(codexRoot.resolve("unmanaged"))
    unmanaged.resolve("SKILL.md").writeText("leave me alone")
    val targets = setOf(
      AgentSkillTargetId("codex", codexRoot),
      AgentSkillTargetId("claude", claudeRoot),
    )
    val workspace = FileSystemMachineSkillWorkspace(home, listOf(codexRoot, claudeRoot))
    val operations = MachineSkillOperationService(workspace)
    val lifecycle = MachineSkillLifecycleService(
      MachineSkillMutationCoordinator(FileSystemMachineSkillTransaction(home, listOf(codexRoot, claudeRoot))),
    )

    val install = operations.previewInstall(InstallMachineSkillRequest(source, targets))
    assertEquals(MachineSkillInstallDecision.INSTALL, install.decision, install.outcomes.toString())
    val installed = lifecycle.apply(install)
    assertNotNull(installed.planId, installed.outcomes.toString())
    val firstTargets = targets.map { Files.readSymbolicLink(it.skillsPath.resolve("demo")) }.toSet()
    assertEquals(1, firstTargets.size)
    assertTrue(Files.isDirectory(firstTargets.single()))

    val reinstall = operations.previewInstall(InstallMachineSkillRequest(source, targets))
    assertEquals(MachineSkillInstallDecision.REINSTALL, reinstall.decision)
    assertNotNull(lifecycle.apply(reinstall).planId)
    assertEquals(firstTargets, targets.map { Files.readSymbolicLink(it.skillsPath.resolve("demo")) }.toSet())

    val record = assertNotNull(workspace.records.readRecord("demo"))
    val edit = EditMachineSkillRequest(
      "demo",
      assertNotNull(workspace.records.recordDigest("demo")),
      record.activeContentHash,
    )
    val editPreview = operations.previewEdit(SaveMachineSkillEditRequest(edit, markdown("edited")))
    assertNotNull(lifecycle.apply(editPreview).planId)
    val editedTargets = targets.map { Files.readSymbolicLink(it.skillsPath.resolve("demo")) }.toSet()
    assertEquals(1, editedTargets.size)
    assertNotEquals(firstTargets.single(), editedTargets.single())
    assertTrue(Files.readString(editedTargets.single().resolve("SKILL.md")).contains("edited"))

    val deletion = operations.previewDelete(DeleteMachineSkillRequest("demo"))
    val deleted = lifecycle.confirmDelete(
      ConfirmMachineSkillDeleteRequest(deletion, assertNotNull(deletion.prepared).plan.planId),
    )
    assertNotNull(deleted.planId)
    targets.forEach { assertFalse(Files.exists(it.skillsPath.resolve("demo"))) }
    assertEquals("leave me alone", Files.readString(unmanaged.resolve("SKILL.md")))
  }

  private fun markdown(body: String) = "---\nname: demo\ndescription: Demo\n---\n$body\n"
}
