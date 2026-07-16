package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
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
import java.awt.Desktop
import java.nio.file.Path
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
    val inspection = runtimeServices.machineSkillToolsFacade.inspectSource(Path.of(path))
    val bundle = inspection.bundle
    return MachineSkillSourceSummary(
      skillName = bundle?.name.orEmpty(),
      description = bundle?.description.orEmpty(),
      sourcePath = inspection.sourcePath.toString(),
      includedFileCount = bundle?.files?.size ?: 0,
      totalBytes = bundle?.totalBytes ?: 0,
      contentIdentity = bundle?.contentHash.orEmpty(),
      validationIssues = inspection.validationIssues,
    )
  }

  override suspend fun assessInstallTargets(sourcePath: String): List<MachineSkillTargetOption> {
    val skillName = inspectSource(sourcePath).skillName
    return runtimeServices.machineSkillToolsFacade.installTargets(skillName).map { target ->
      MachineSkillTargetOption(
        id = target.id.stableIdentity,
        provider = target.id.provider,
        path = target.id.skillsPath.toString(),
        detected = target.detected,
        conflict = target.conflictPath?.let { "Existing copy at $it" },
      )
    }
  }

  override suspend fun previewInstall(sourcePath: String, targetIds: Set<String>): MachineSkillPreviewPresentation =
    runtimeServices.machineSkillToolsFacade.previewInstall(
      Path.of(sourcePath),
      runtimeServices.machineSkillToolsFacade.installTargets(inspectSource(sourcePath).skillName)
        .map { it.id }.filter { it.stableIdentity in targetIds }.toSet(),
    ).let { preview ->
      val prepared = requireNotNull(preview.prepared) {
        preview.outcomes.joinToString { "${it.code}: ${it.detail}" }.ifEmpty { "Install preview was blocked." }
      }
      MachineSkillPreviewPresentation(
        planId = prepared.plan.planId,
        operations = prepared.plan.mutations.map { mutation ->
          skillbill.desktop.core.domain.model.MachineSkillPreviewLine(
            mutation.operation.name,
            mutation.path.toString(),
            mutation.outcome.name,
          )
        },
        warnings = prepared.plan.warnings.map { it.message } + preview.outcomes.map { it.detail },
      )
    }

  override suspend fun apply(planId: String): MachineSkillApplyPresentation =
    runtimeServices.machineSkillToolsFacade.apply(planId).let { result ->
      MachineSkillApplyPresentation(result.outcomes.map { outcome ->
        skillbill.desktop.core.domain.model.MachineSkillTargetResult(
          outcome.target?.stableIdentity ?: outcome.path?.toString() ?: result.skillName,
          outcome.kind.name,
          outcome.detail,
        )
      })
    }

  override suspend fun inventory(): MachineSkillInventoryPresentation = mapInventory(
    runtimeServices.machineSkillToolsFacade.inventory(),
  )

  override suspend fun refreshInventory(): MachineSkillInventoryPresentation = mapInventory(
    runtimeServices.machineSkillToolsFacade.refreshInventory(),
  )

  override suspend fun revealSource(skillName: String): Result<Unit> =
    runCatching {
      val source = requireNotNull(runtimeServices.machineSkillToolsFacade.canonicalSource(skillName)) {
        "Managed source is unavailable."
      }
      Desktop.getDesktop().open(source.toFile())
    }

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
      val record = runtimeServices.machineSkillToolsFacade.managedRecord(row.normalizedName)
      val latest = runtimeServices.machineSkillToolsFacade.latestResult(row.normalizedName)
      row.displayName to MachineSkillManagerDetail(
        name = row.displayName,
        description = "",
        ownership = row.ownership.name,
        provenance = row.targetPresence.flatMap { it.occurrences }.flatMap { it.provenance }.distinct(),
        canonicalManagedSourcePath = record?.let {
          runtimeServices.machineSkillToolsFacade.canonicalSource(row.normalizedName)?.toString()
        },
        activeSnapshotHash = record?.activeContentHash,
        recordIdentity = record?.let { runtimeServices.machineSkillToolsFacade.managedRecordDigest(row.normalizedName) },
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
        lastMutationResult = latest?.outcomes?.map { outcome ->
          skillbill.desktop.core.domain.model.MachineSkillTargetResult(
            outcome.target?.stableIdentity ?: outcome.path?.toString() ?: latest.skillName,
            outcome.kind.name,
            outcome.detail,
          )
        }.orEmpty(),
        repairAvailable = row.health.name != "HEALTHY" && row.ownership.name == "MANAGED",
      )
    }
    return MachineSkillInventoryPresentation(rows, details)
  }
}
