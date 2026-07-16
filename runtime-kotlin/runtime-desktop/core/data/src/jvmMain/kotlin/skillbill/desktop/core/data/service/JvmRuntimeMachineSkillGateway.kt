package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.application.model.MachineSkillInventoryRequest
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.data.di.DesktopRuntimeApplicationServices
import skillbill.desktop.core.domain.model.MachineSkillManagerDetail
import skillbill.desktop.core.domain.model.MachineSkillManagerRow
import skillbill.desktop.core.domain.model.MachineSkillSourceSummary
import skillbill.desktop.core.domain.model.MachineSkillTargetDetail
import skillbill.desktop.core.domain.model.MachineSkillTargetOption
import skillbill.desktop.core.domain.service.MachineSkillApplyPresentation
import skillbill.desktop.core.domain.service.MachineSkillInventoryPresentation
import skillbill.desktop.core.domain.service.MachineSkillPreviewPresentation
import skillbill.desktop.core.domain.service.MachineSkillSourceChoice
import skillbill.desktop.core.domain.service.RuntimeMachineSkillGateway
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.swing.JFileChooser

@Inject
@SingleIn(UserScope::class)
class JvmRuntimeMachineSkillGateway(
  private val runtimeServices: DesktopRuntimeApplicationServices =
    DesktopRuntimeApplicationServices.forCurrentUserHome(),
) : RuntimeMachineSkillGateway {
  private val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()

  override suspend fun chooseSource(): MachineSkillSourceChoice {
    val chooser = JFileChooser(home.toFile()).apply {
      fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
      dialogTitle = "Choose SKILL.md or skill directory"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
      MachineSkillSourceChoice.Selected(chooser.selectedFile.toPath().toAbsolutePath().normalize().toString())
    } else {
      MachineSkillSourceChoice.Cancelled
    }
  }

  override suspend fun inspectSource(path: String): MachineSkillSourceSummary {
    val selected = Path.of(path).toAbsolutePath().normalize()
    val root = if (Files.isDirectory(selected)) selected else selected.parent
    val skillFile = if (Files.isDirectory(selected)) selected.resolve("SKILL.md") else selected
    require(skillFile.fileName.toString() == "SKILL.md" && Files.isRegularFile(skillFile)) {
      "Select a SKILL.md file or a directory containing one."
    }
    val files = Files.walk(root).use { stream -> stream.filter(Files::isRegularFile).sorted().toList() }
    val markdown = Files.readString(skillFile)
    val name = frontmatterValue(markdown, "name") ?: root.fileName.toString()
    val description = frontmatterValue(markdown, "description").orEmpty()
    val digest = MessageDigest.getInstance("SHA-256")
    files.forEach { file ->
      digest.update(root.relativize(file).toString().toByteArray())
      digest.update(Files.readAllBytes(file))
    }
    return MachineSkillSourceSummary(
      skillName = name,
      description = description,
      sourcePath = selected.toString(),
      includedFileCount = files.size,
      totalBytes = files.sumOf(Files::size),
      contentIdentity = digest.digest().joinToString("") { "%02x".format(it) },
      validationIssues = if (name.isBlank()) listOf("Skill name is missing.") else emptyList(),
    )
  }

  override suspend fun assessInstallTargets(sourcePath: String): List<MachineSkillTargetOption> {
    val skillName = inspectSource(sourcePath).skillName
    return runtimeServices.installAgentService.detectAgentTargets(home).map { target ->
      val destination = target.path.resolve(skillName)
      MachineSkillTargetOption(
        id = "${target.name}:${target.path.toAbsolutePath().normalize()}",
        provider = target.name,
        path = target.path.toAbsolutePath().normalize().toString(),
        detected = true,
        conflict = if (Files.exists(destination)) "Existing copy at $destination" else null,
      )
    }
  }

  override suspend fun previewInstall(sourcePath: String, targetIds: Set<String>): MachineSkillPreviewPresentation =
    throw UnsupportedOperationException("Install mutation preview requires the shared machine-skill facade.")

  override suspend fun apply(planId: String): MachineSkillApplyPresentation =
    throw UnsupportedOperationException("Install mutation apply requires the shared machine-skill facade.")

  override suspend fun inventory(): MachineSkillInventoryPresentation = mapInventory(
    runtimeServices.machineSkillInventoryService.inventory(MachineSkillInventoryRequest(home)),
  )

  override suspend fun refreshInventory(): MachineSkillInventoryPresentation = mapInventory(
    runtimeServices.machineSkillRefreshService.refresh(MachineSkillInventoryRequest(home)).snapshot,
  )

  override suspend fun revealSource(skillName: String): Result<Unit> =
    Result.failure(UnsupportedOperationException("Managed source reveal requires inventory provenance."))

  override suspend fun acknowledgePostMortem(): Result<Unit> = Result.success(Unit)

  private fun mapInventory(snapshot: skillbill.managedskill.model.MachineSkillInventorySnapshot): MachineSkillInventoryPresentation {
    val rows = snapshot.rows.map { row ->
      MachineSkillManagerRow(
        name = row.displayName,
        description = "",
        ownership = row.ownership.name,
        health = row.health.name,
        agents = row.targetPresence.filter { it.present }.map { it.target.provider }.toSet(),
      )
    }
    val details = snapshot.rows.associate { row ->
      row.displayName to MachineSkillManagerDetail(
        name = row.displayName,
        description = "",
        ownership = row.ownership.name,
        provenance = row.targetPresence.flatMap { it.occurrences }.flatMap { it.provenance }.distinct(),
        canonicalManagedSourcePath = null,
        activeSnapshotHash = null,
        recordIdentity = null,
        contentIdentity = row.contentHashes.singleOrNull(),
        targets = row.targetPresence.map { presence ->
          MachineSkillTargetDetail(
            provider = presence.target.provider,
            path = presence.target.skillsPath.toString(),
            detectionStatus = if (snapshot.targets.any { it.id == presence.target && it.detected }) "DETECTED" else "NOT_DETECTED",
            state = if (presence.present) "PRESENT" else "ABSENT",
            contentIdentity = presence.occurrences.mapNotNull { it.contentHash }.distinct().singleOrNull(),
          )
        },
        validationIssues = row.issues.map { "${it.code}: ${it.message}" },
        repairAvailable = row.health.name != "HEALTHY" && row.ownership.name == "MANAGED",
      )
    }
    return MachineSkillInventoryPresentation(rows, details)
  }
}

private fun frontmatterValue(markdown: String, key: String): String? = markdown.lineSequence()
  .drop(1)
  .takeWhile { it != "---" }
  .firstOrNull { it.startsWith("$key:") }
  ?.substringAfter(':')
  ?.trim()
  ?.trim('"', '\'')
